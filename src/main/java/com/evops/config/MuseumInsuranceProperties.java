package com.evops.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文物保险估值配置（museum.insurance.*）：
 * 估值过期提醒定时扫描间隔与启动补扫开关。
 * 提醒只提示续保/重新评估，不冻结借展归还。
 */
@Data
@Component
@ConfigurationProperties(prefix = "museum.insurance")
public class MuseumInsuranceProperties {

    /** 过期提醒扫描间隔（毫秒） */
    private long remindScanIntervalMs = 60000;

    /** 应用就绪后是否立即补扫一次（重启场景） */
    private boolean remindScanOnStartup = true;
}
