package com.evops.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文物运输包装配置（museum.packaging.*）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "museum.packaging")
public class MuseumPackagingProperties {

    /** 外部修复依赖装箱确认通知地址；为空时按依赖不可达处理（不假装成功） */
    private String repairNotifyUrl = "";

    /** 外部通知 HTTP 超时（毫秒） */
    private int repairNotifyTimeoutMs = 3000;

    /** 待送达通知补偿扫描间隔（毫秒）；恢复后按此频率重试，行级认领保证不重复通知 */
    private long notifyScanIntervalMs = 300_000L;

    /** 应用启动后是否立即补投一次历史待送达通知（重启恢复场景） */
    private boolean notifyScanOnStartup = true;
}
