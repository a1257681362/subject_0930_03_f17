package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.config.MuseumMaterialProperties;
import com.evops.entity.MaterialReview;
import com.evops.entity.MaterialUsage;
import com.evops.entity.MaterialUsageLine;
import com.evops.mapper.MaterialReviewMapper;
import com.evops.mapper.MaterialUsageLineMapper;
import com.evops.mapper.MaterialUsageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import static com.evops.constant.MuseumConstants.*;

/**
 * 用量差异复核定时扫描：
 *
 * <p>周期扫描 reviewStatus=PENDING 且尚无复核记录的领用明细行，补登复核单并写台账。
 * 凭 {@code t_material_review.uk_review_line} 唯一约束实现<b>重启幂等</b>：
 * 定时任务重启后对历史数据全量重扫，同一明细行只会有一条复核记录、一条台账流水，
 * 不会多写记录；操作者从原领用明细行原样带入，不会丢失。</p>
 *
 * <p>应用启动就绪后先补扫一次（重启场景），之后按固定间隔扫描；
 * 单次扫描串行加锁，启动扫描与周期扫描不会并发重入。</p>
 */
@Component
public class MaterialReviewScheduler {

    private static final Logger log = LoggerFactory.getLogger(MaterialReviewScheduler.class);

    private final MaterialUsageLineMapper lineMapper;
    private final MaterialReviewMapper reviewMapper;
    private final MaterialUsageMapper usageMapper;
    private final LedgerService ledgerService;
    private final MuseumMaterialProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final ReentrantLock scanLock = new ReentrantLock();
    private final AtomicBoolean startedUp = new AtomicBoolean(false);

    public MaterialReviewScheduler(MaterialUsageLineMapper lineMapper,
                                   MaterialReviewMapper reviewMapper,
                                   MaterialUsageMapper usageMapper,
                                   LedgerService ledgerService,
                                   MuseumMaterialProperties properties,
                                   PlatformTransactionManager transactionManager) {
        this.lineMapper = lineMapper;
        this.reviewMapper = reviewMapper;
        this.usageMapper = usageMapper;
        this.ledgerService = ledgerService;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** 启动就绪补扫：重启定时任务后立即检查历史待复核明细 */
    @EventListener(ApplicationReadyEvent.class)
    public void scanOnStartup() {
        if (properties.isReviewScanOnStartup() && startedUp.compareAndSet(false, true)) {
            log.info("修复材料差异复核：启动补扫历史待复核明细");
            scanOnce();
        }
    }

    /** 周期扫描（fixedDelay：上一轮结束后再计时，任务堆积不会重叠） */
    @Scheduled(fixedDelayString = "${museum.material.review-scan-interval-ms:60000}")
    public void scanOnSchedule() {
        scanOnce();
    }

    /**
     * 执行一轮扫描，返回本轮新登记的复核记录数。
     * 公开供接口/测试手动触发；加锁保证与定时轮次不重入。
     */
    public int scanOnce() {
        if (!scanLock.tryLock()) {
            log.debug("差异复核扫描上一轮尚未结束，本轮跳过");
            return 0;
        }
        try {
            List<MaterialUsageLine> pending = lineMapper.selectList(
                    new LambdaQueryWrapper<MaterialUsageLine>()
                            .eq(MaterialUsageLine::getReviewStatus, REVIEW_PENDING)
                            .orderByAsc(MaterialUsageLine::getId));
            int created = 0;
            for (MaterialUsageLine line : pending) {
                if (reviewExists(line.getId())) {
                    continue;
                }
                if (registerReview(line)) {
                    created++;
                }
            }
            if (created > 0) {
                log.info("修复材料差异复核扫描：新增待复核记录 {} 条", created);
            }
            return created;
        } finally {
            scanLock.unlock();
        }
    }

    private boolean reviewExists(Long lineId) {
        return reviewMapper.selectCount(new LambdaQueryWrapper<MaterialReview>()
                .eq(MaterialReview::getLineId, lineId)) > 0;
    }

    /**
     * 单行独立事务登记复核 + 台账。并发/重启竞态撞唯一约束时整体回滚该行，
     * 由调用方跳过，不影响其他行，也不会留下半成品记录。
     */
    private boolean registerReview(MaterialUsageLine line) {
        try {
            return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
                // 事务内二次检查，挡住快速路径后的并发与重启重入
                if (reviewExists(line.getId())) {
                    return false;
                }
                MaterialUsage usage = usageMapper.selectById(line.getUsageId());
                LocalDateTime now = LocalDateTime.now();

                MaterialReview review = new MaterialReview();
                review.setLineId(line.getId());
                review.setUsageId(line.getUsageId());
                review.setProjectId(usage == null ? null : usage.getProjectId());
                review.setRelicId(usage == null ? null : usage.getRelicId());
                review.setPlanQty(line.getPlanQty());
                review.setActualQty(line.getActualQty());
                review.setVariancePct(line.getVariancePct());
                review.setThresholdPct(properties.getVarianceThresholdPct());
                // 操作者取自原领用行快照，复核流程不改写、不丢失
                review.setOperator(line.getOperator());
                review.setApplyPart(line.getApplyPart());
                review.setStatus(REVIEW_OPEN);
                review.setDetectedAt(now);
                reviewMapper.insert(review);

                if (usage != null) {
                    ledgerService.append(usage.getRelicId(), usage.getProjectId(), null,
                            ACTION_MATERIAL_REVIEW, String.valueOf(review.getId()),
                            "材料【" + line.getMaterialName() + "】批号 " + line.getBatchNo()
                                    + " 用量差异 " + line.getVariancePct() + "% 超过阈值 "
                                    + properties.getVarianceThresholdPct()
                                    + "%，部位：" + line.getApplyPart()
                                    + "，操作者：" + line.getOperator() + "，触发复核", now);
                }
                return true;
            }));
        } catch (DuplicateKeyException e) {
            // 重启/并发下已由另一轮扫描登记：不重复写记录
            log.debug("差异复核记录已存在，lineId={}，跳过", line.getId());
            return false;
        }
    }
}
