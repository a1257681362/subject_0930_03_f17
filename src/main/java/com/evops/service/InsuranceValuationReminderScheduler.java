package com.evops.service;

import com.evops.config.MuseumInsuranceProperties;
import com.evops.entity.InsuranceValuationVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 文物保险估值过期提醒扫描（museum-15）：
 *
 * <p>周期扫描 status=ACTIVE 且 effective_to 已过的估值版本，逐版本在独立事务中
 * 生成 OPEN 提醒并写台账。<b>提醒只提示、不改变任何业务状态，尤其不冻结借展
 * 归还</b>：在借文物估值过期时归还验收照常进行，借展口径仍以签约快照为准。</p>
 *
 * <p>应用就绪后先补扫一次（重启场景），之后固定间隔扫描；串行加锁不重入。
 * 是否启用与间隔由 {@code museum.insurance.remind-scan-*} 配置。</p>
 */
@Component
public class InsuranceValuationReminderScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(InsuranceValuationReminderScheduler.class);

    private final InsuranceValuationService valuationService;
    private final MuseumInsuranceProperties properties;
    private final ReentrantLock scanLock = new ReentrantLock();
    private final AtomicBoolean startedUp = new AtomicBoolean(false);

    public InsuranceValuationReminderScheduler(InsuranceValuationService valuationService,
                                               MuseumInsuranceProperties properties) {
        this.valuationService = valuationService;
        this.properties = properties;
    }

    /** 启动就绪补扫：重启后立即为停机期间到期的估值补提醒 */
    @EventListener(ApplicationReadyEvent.class)
    public void scanOnStartup() {
        if (properties.isRemindScanOnStartup() && startedUp.compareAndSet(false, true)) {
            log.info("保险估值过期提醒：启动补扫到期估值");
            scanOnce();
        }
    }

    /** 周期扫描（fixedDelay：上一轮结束后再计时，不重叠） */
    @Scheduled(fixedDelayString = "${museum.insurance.remind-scan-interval-ms:60000}")
    public void scanOnSchedule() {
        scanOnce();
    }

    /**
     * 执行一轮扫描，返回本轮新生成的提醒数。公开供接口/测试手动触发。
     * 逐版本独立事务在 valuationService.remindIfExpired 内，单条失败不影响其他版本。
     */
    public int scanOnce() {
        if (!scanLock.tryLock()) {
            log.debug("保险估值过期提醒扫描上一轮尚未结束，本轮跳过");
            return 0;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            List<InsuranceValuationVersion> actives = valuationService.listActiveVersions();
            int reminded = 0;
            for (InsuranceValuationVersion v : actives) {
                if (valuationService.remindIfExpired(v.getId(), now) != null) {
                    reminded++;
                }
            }
            if (reminded > 0) {
                log.info("保险估值过期提醒扫描：新增 {} 条到期提醒（提醒不冻结借展归还）", reminded);
            }
            return reminded;
        } finally {
            scanLock.unlock();
        }
    }
}
