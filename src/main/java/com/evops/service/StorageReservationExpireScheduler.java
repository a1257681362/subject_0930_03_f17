package com.evops.service;

import com.evops.config.MuseumStorageProperties;
import com.evops.entity.StorageReservation;
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
 * 预约超时自动释放扫描：
 *
 * <p>周期扫描 status=RESERVED 且 expire_at 已过的预约，逐条在独立事务中置 EXPIRED、
 * 清空格位持有键并写台账。释放条件 UPDATE 的 WHERE 只命中 RESERVED——
 * <b>已开始移动（MOVING）的格位永不释放</b>。</p>
 *
 * <p>应用就绪后先补扫一次（重启场景，释放停机期间到期的预约），之后按固定间隔扫描；
 * 单次扫描串行加锁，启动扫描与周期扫描不重入。</p>
 */
@Component
public class StorageReservationExpireScheduler {

    private static final Logger log = LoggerFactory.getLogger(StorageReservationExpireScheduler.class);

    private final StorageReservationService reservationService;
    private final MuseumStorageProperties properties;
    private final ReentrantLock scanLock = new ReentrantLock();
    private final AtomicBoolean startedUp = new AtomicBoolean(false);

    public StorageReservationExpireScheduler(StorageReservationService reservationService,
                                             MuseumStorageProperties properties) {
        this.reservationService = reservationService;
        this.properties = properties;
    }

    /** 启动就绪补扫：重启后立即释放停机期间超时、尚未开工的预约 */
    @EventListener(ApplicationReadyEvent.class)
    public void scanOnStartup() {
        if (properties.isExpireScanOnStartup() && startedUp.compareAndSet(false, true)) {
            log.info("库位预约超时释放：启动补扫到期未开工预约");
            scanOnce();
        }
    }

    /** 周期扫描（fixedDelay：上一轮结束后再计时，不重叠） */
    @Scheduled(fixedDelayString = "${museum.storage.expire-scan-interval-ms:60000}")
    public void scanOnSchedule() {
        scanOnce();
    }

    /**
     * 执行一轮扫描，返回本轮自动释放的预约数。
     * 公开供接口/测试手动触发；加锁保证与定时轮次不重入。
     */
    public int scanOnce() {
        if (!scanLock.tryLock()) {
            log.debug("预约超时释放扫描上一轮尚未结束，本轮跳过");
            return 0;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            List<StorageReservation> due = reservationService.listDueReservations(now);
            int released = 0;
            for (StorageReservation reservation : due) {
                // 逐条独立事务：释放条件 UPDATE 只命中 RESERVED，MOVING 永不释放
                if (reservationService.releaseIfTimedOut(reservation.getId(), now)) {
                    released++;
                }
            }
            if (released > 0) {
                log.info("库位预约超时释放扫描：自动释放 {} 条到期预约（已开工移动不释放）", released);
            }
            return released;
        } finally {
            scanLock.unlock();
        }
    }
}
