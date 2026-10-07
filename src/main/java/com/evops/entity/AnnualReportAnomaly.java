package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 年度报表异常凭证：只追加。
 *
 * <p>同一异常（同 anomalyType + sourceId）可多次追加凭证（补充现场材料），
 * {@code voucherSeq} 递增；永不覆盖、不删除已有凭证。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_annual_report_anomaly")
public class AnnualReportAnomaly extends BaseEntity {

    private Long reportId;

    /** ENV_EVENT / EQUIPMENT_FAULT */
    private String anomalyType;

    /** 异常来源行 id（t_env_event.id / t_repair_equipment_fault.id） */
    private Long sourceId;

    /** 异常来源单号（EE-xxxxxx / FT-xxxxxx） */
    private String sourceNo;

    private String campusCode;

    private String secretLevel;

    private String title;

    /** 凭证内容/补充说明 */
    private String detail;

    private LocalDateTime occurredAt;

    /** 同一异常的凭证序号，从 1 递增 */
    private Integer voucherSeq;

    private String appendedBy;

    private LocalDateTime appendedAt;
}
