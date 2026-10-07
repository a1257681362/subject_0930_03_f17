package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 临时替代包装审批：一替代装箱单一条，只追加。
 *
 * <p>固化替代包装实际能力（sub_*）与对标准规格的逐项差异（*_diff/diffDetail），
 * 差异在申请时即留痕；审批通过后装箱单才能进入外部确认与发运，驳回则装箱单
 * 终态 REJECTED。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_pack_substitute")
public class PackSubstitute extends BaseEntity {

    private String substituteNo;
    private Long orderId;

    private String reason;
    private String applicant;

    private String subSizeCode;
    private String subCushionLevel;
    private String subClimateZone;
    private BigDecimal subMaxWeightG;
    private BigDecimal subShockLimitG;
    private BigDecimal subFitLengthMinMm;
    private BigDecimal subFitLengthMaxMm;
    private BigDecimal subFitWidthMinMm;
    private BigDecimal subFitWidthMaxMm;
    private BigDecimal subFitDepthMinMm;
    private BigDecimal subFitDepthMaxMm;

    /** 与标准规格逐项差异（申请时固化） */
    private String cushionDiff;
    private String shockDiff;
    private String weightDiff;
    private String sizeDiff;
    private String climateDiff;
    private String diffDetail;

    /** PENDING / APPROVED / REJECTED */
    private String status;
    private String approver;
    /** 1 通过 / 0 驳回，PENDING 时为 null */
    private Integer approved;
    private LocalDateTime approvedAt;
    private String approvalOpinion;
}
