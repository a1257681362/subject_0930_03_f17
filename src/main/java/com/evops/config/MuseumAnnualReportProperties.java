package com.evops.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 年度报表（museum-16）调度配置。
 * cron 留空表示不启用周期触发（Quartz 任务仍保留，可由接口立即触发）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "museum.annual-report")
public class MuseumAnnualReportProperties {

    /** Quartz 年度生成 cron（如 0 0 2 5 1 ? 为每年 1 月 5 日 02:00）；留空不注册触发器 */
    private String cron = "";
}
