package com.evops.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 库房库位与密集架预约配置（museum.storage.*）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "museum.storage")
public class MuseumStorageProperties {

    /** 预约保留时长（分钟）：expire_at = 预约时间 + 该值，超时未开工自动释放 */
    private long reservationExpireMinutes = 30L;

    /** 超时释放定时扫描间隔（毫秒），重启后按此频率继续幂等扫描 */
    private long expireScanIntervalMs = 60_000L;

    /** 应用启动后是否立即扫描一次超时预约 */
    private boolean expireScanOnStartup = true;
}
