package com.evops.service;

import com.evops.entity.AnnualReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;

import static com.evops.constant.MuseumConstants.TRIGGER_SCHEDULED;

/**
 * 年度报表生成调度入口（museum-16）。
 *
 * <p>Quartz 触发器与手动接口共用本入口：串行加锁，上一轮未结束则本轮跳过；
 * 实际统计/快照/任务日志/失败告警全部在 {@link AnnualReportService#runGeneration}
 * 内完成（RUNNING→SUCCESS/FAILED 日志全程保留，失败追加 OPEN 告警）。</p>
 */
@Component
public class AnnualReportGenerationScheduler {

    private static final Logger log = LoggerFactory.getLogger(AnnualReportGenerationScheduler.class);

    private final AnnualReportService annualReportService;
    private final ReentrantLock runLock = new ReentrantLock();

    public AnnualReportGenerationScheduler(AnnualReportService annualReportService) {
        this.annualReportService = annualReportService;
    }

    /** Quartz 定时触发：拿不到锁说明上一轮仍在执行，本轮跳过（不堆积并发）。 */
    public AnnualReport fireScheduled(Integer year, String campusScope, String generatedBy, String fireKey) {
        if (!runLock.tryLock()) {
            log.warn("年度报表生成上一轮尚未结束，本轮定时触发跳过 year={} fireKey={}", year, fireKey);
            return null;
        }
        try {
            log.info("Quartz 触发年度报表生成 year={} campus={} fireKey={}", year, campusScope, fireKey);
            return annualReportService.runGeneration(year, null, campusScope, generatedBy,
                    TRIGGER_SCHEDULED, fireKey);
        } finally {
            runLock.unlock();
        }
    }

    /** 手动触发（接口/测试用）：同样串行，失败由服务层记 FAILED 日志并告警。 */
    public AnnualReport fireManual(Integer year, Long caliberVersionId, String campusScope,
                                   String generatedBy, String fireKey) {
        if (!runLock.tryLock()) {
            throw new IllegalStateException("年度报表生成正在执行，请稍后再试");
        }
        try {
            return annualReportService.runGeneration(year, caliberVersionId, campusScope,
                    generatedBy, com.evops.constant.MuseumConstants.TRIGGER_MANUAL, fireKey);
        } finally {
            runLock.unlock();
        }
    }
}
