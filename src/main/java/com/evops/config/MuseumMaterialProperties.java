package com.evops.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 修复材料核算配置（museum.material.*）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "museum.material")
public class MuseumMaterialProperties {

    /** 计划/实际用量差异复核阈值（百分比），实际相对计划偏差严格超过该值即触发复核 */
    private BigDecimal varianceThresholdPct = new BigDecimal("20");

    /** 差异复核定时扫描频率（毫秒），重启后按此频率继续幂等扫描 */
    private long reviewScanIntervalMs = 60_000L;

    /** 应用启动后是否立即扫描一次历史待复核明细 */
    private boolean reviewScanOnStartup = true;
}
