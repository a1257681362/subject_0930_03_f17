package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.entity.PackNotifyOutbox;
import com.evops.gateway.MuseumRepairUnavailableException;
import com.evops.mapper.PackNotifyOutboxMapper;
import com.evops.config.MuseumPackagingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import static com.evops.constant.MuseumConstants.NOTIFY_PENDING;

/**
 * 装箱确认通知补偿：外部修复依赖无响应后，恢复自动补投。
 *
 * <p>启动就绪补扫一轮（重启恢复），之后按固定间隔扫描；单轮串行加锁不重入。
 * 投递仍走 {@link PackNotifyService} 的行级认领与状态机——无响应继续等待，
 * SENT 永不重投，因此恢复后同一装箱单<b>只通知一次</b>。</p>
 */
@Component
public class PackNotifyRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(PackNotifyRetryScheduler.class);

    private final PackNotifyOutboxMapper outboxMapper;
    private final PackNotifyService notifyService;
    private final MuseumPackagingProperties properties;
    private final ReentrantLock scanLock = new ReentrantLock();
    private final AtomicBoolean startedUp = new AtomicBoolean(false);

    public PackNotifyRetryScheduler(PackNotifyOutboxMapper outboxMapper,
                                    PackNotifyService notifyService,
                                    MuseumPackagingProperties properties) {
        this.outboxMapper = outboxMapper;
        this.notifyService = notifyService;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void retryOnStartup() {
        if (properties.isNotifyScanOnStartup() && startedUp.compareAndSet(false, true)) {
            log.info("运输包装外部通知：启动补投历史待送达通知");
            retryOnce();
        }
    }

    @Scheduled(fixedDelayString = "${museum.packaging.notify-scan-interval-ms:300000}")
    public void retryOnSchedule() {
        retryOnce();
    }

    /** 执行一轮补偿，返回本轮送达条数；加锁保证与启动/手动轮次不重入 */
    public int retryOnce() {
        if (!scanLock.tryLock()) {
            log.debug("外部通知补偿上一轮尚未结束，本轮跳过");
            return 0;
        }
        try {
            // 崩溃停在 SENDING 的行先复位，再统一补偿 PENDING
            notifyService.resetSendingOrphans();
            List<PackNotifyOutbox> pending = outboxMapper.selectList(
                    new LambdaQueryWrapper<PackNotifyOutbox>()
                            .eq(PackNotifyOutbox::getStatus, NOTIFY_PENDING)
                            .orderByAsc(PackNotifyOutbox::getId));
            int sent = 0;
            for (PackNotifyOutbox outbox : pending) {
                try {
                    notifyService.deliverOrder(outbox.getOrderId());
                    sent++;
                } catch (MuseumRepairUnavailableException e) {
                    // 外部仍无响应：保持 PENDING，下一轮再试，绝不假装成功
                    log.warn("外部修复依赖仍无响应，装箱单 {} 通知待恢复后补投", outbox.getOrderId());
                }
            }
            if (sent > 0) {
                log.info("运输包装外部通知补偿：本轮送达 {} 条（恢复后每条仅通知一次）", sent);
            }
            return sent;
        } finally {
            scanLock.unlock();
        }
    }
}
