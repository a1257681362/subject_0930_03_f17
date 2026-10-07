package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 年度报表生成失败告警：任务 FAILED 时追加一条 OPEN，人工确认后置 ACKNOWLEDGED。
 * 告警只追加，确认不改写失败事实。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_annual_report_alert")
public class AnnualReportAlert extends BaseEntity {

    /** 告警单号，全库唯一，形如 ARA-000001 */
    private String alertNo;

    private Long jobLogId;

    private Integer reportYear;

    private String reason;

    /** OPEN / ACKNOWLEDGED */
    private String status;

    private LocalDateTime createdAt;

    private String acknowledgedBy;

    private LocalDateTime acknowledgedAt;

    private String ackNote;
}
