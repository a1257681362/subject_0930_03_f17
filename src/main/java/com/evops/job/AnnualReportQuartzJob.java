package com.evops.job;

import com.evops.service.AnnualReportGenerationScheduler;
import com.evops.config.ApplicationContextHolder;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;

/**
 * 年度报表生成 Quartz 任务（museum-16）。
 *
 * <p>{@link DisallowConcurrentExecution} 禁止 Quartz 层并发重入，串行再由调度
 * 服务内的 ReentrantLock 双保险。Quartz 每次执行新建 Job 实例（非容器管理），
 * 通过 {@link ApplicationContextHolder} 取用调度服务。
 * 生成失败时服务层已保留 FAILED 任务日志并追加 OPEN 告警，
 * 此处仅记录错误日志，不再向上伪装成功。</p>
 */
@DisallowConcurrentExecution
public class AnnualReportQuartzJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(AnnualReportQuartzJob.class);

    /** JobDataMap 键：统计年度（缺省为当前年的上一年度） */
    public static final String DATA_YEAR = "year";
    /** JobDataMap 键：馆区范围（可空=全馆区） */
    public static final String DATA_CAMPUS = "campusScope";
    /** JobDataMap 键：生成人（系统操作留痕） */
    public static final String DATA_OPERATOR = "generatedBy";

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        Object yearValue = context.getMergedJobDataMap().get(DATA_YEAR);
        Integer year = yearValue == null
                ? LocalDate.now().getYear() - 1
                : ((Number) yearValue).intValue();
        String campusScope = context.getMergedJobDataMap().getString(DATA_CAMPUS);
        String generatedBy = context.getMergedJobDataMap().getString(DATA_OPERATOR);
        if (generatedBy == null || generatedBy.trim().isEmpty()) {
            generatedBy = "系统定时";
        }
        String fireKey = context.getFireInstanceId();
        try {
            ApplicationContextHolder.getBean(AnnualReportGenerationScheduler.class)
                    .fireScheduled(year, campusScope, generatedBy, fireKey);
        } catch (Exception e) {
            log.error("Quartz 年度报表生成失败 year={} fireKey={}", year, fireKey, e);
        }
    }
}
