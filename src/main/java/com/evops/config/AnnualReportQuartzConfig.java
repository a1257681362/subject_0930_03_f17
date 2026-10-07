package com.evops.config;

import com.evops.job.AnnualReportQuartzJob;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 年度报表 Quartz 调度装配（museum-16）。
 *
 * <p>仅当 {@code museum.annual-report.cron} 非空时注册年度生成任务；
 * cron 留空（默认）不注册触发器，仍可通过手动接口立即生成。
 * 任务声明 @DisallowConcurrentExecution，与服务层 ReentrantLock 双保险防重叠。</p>
 */
@Component
public class AnnualReportQuartzConfig {

    private static final Logger log = LoggerFactory.getLogger(AnnualReportQuartzConfig.class);
    private static final JobKey JOB_KEY = new JobKey("annualReportGenerationJob", "ANNUAL_REPORT");
    private static final TriggerKey TRIGGER_KEY = new TriggerKey("annualReportGenerationTrigger", "ANNUAL_REPORT");

    private final Scheduler scheduler;
    private final MuseumAnnualReportProperties properties;

    public AnnualReportQuartzConfig(Scheduler scheduler, MuseumAnnualReportProperties properties) {
        this.scheduler = scheduler;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void register() throws SchedulerException {
        String cron = properties.getCron();
        if (cron == null || cron.trim().isEmpty()) {
            log.info("年度报表 Quartz cron 未配置（museum.annual-report.cron 留空），不注册定时触发器");
            return;
        }
        JobDetail jobDetail = JobBuilder.newJob(AnnualReportQuartzJob.class)
                .withIdentity(JOB_KEY)
                .storeDurably()
                .withDescription("文物修复年度报表年度生成任务")
                .build();
        CronTrigger trigger = TriggerBuilder.newTrigger()
                .withIdentity(TRIGGER_KEY)
                .forJob(JOB_KEY)
                .withSchedule(CronScheduleBuilder.cronSchedule(cron.trim())
                        .withMisfireHandlingInstructionDoNothing())
                .build();
        scheduler.addJob(jobDetail, true, true);
        if (scheduler.checkExists(TRIGGER_KEY)) {
            scheduler.rescheduleJob(TRIGGER_KEY, trigger);
        } else {
            scheduler.scheduleJob(trigger);
        }
        log.info("年度报表 Quartz 任务已注册，cron={}", cron);
    }
}
