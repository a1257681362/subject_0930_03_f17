package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 年度报表 Quartz 生成任务日志：每次触发（定时/手动）一条，只追加保留。
 * RUNNING -> SUCCESS / FAILED；失败不删日志、不改写，另出告警。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_annual_report_job_log")
public class AnnualReportJobLog extends BaseEntity {

    private Integer reportYear;

    private Long caliberVersionId;

    private String campusScope;

    /** MANUAL / SCHEDULED */
    private String triggeredBy;

    /** Quartz 触发标识（fireKey），用于辨认同一批次的不同触发 */
    private String fireKey;

    private Long reportId;

    private String reportNo;

    /** RUNNING / SUCCESS / FAILED */
    private String status;

    /** 失败原因（异常堆栈摘要，保留用于排查） */
    private String errorMessage;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;
}
