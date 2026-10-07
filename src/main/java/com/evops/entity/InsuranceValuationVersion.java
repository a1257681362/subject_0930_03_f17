package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 文物保险估值版本（museum-15）：按文物只追加。
 *
 * <p>固化估值来源（购入/评估/拍卖/捐赠）、评估机构、币种与有效期；
 * 金额修订必须另出新版本（version_no 文物内递增），旧版本置
 * {@code SUPERSEDED} 永不改写；新版本须经 FIRST 初审 + SECOND 复核
 * 两名不同人员审批齐备后才置 {@code ACTIVE}，生效同时出具保险批单并回填。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_ins_valuation_version")
public class InsuranceValuationVersion extends BaseEntity {

    /** 估值单号，全库唯一 */
    private String valuationNo;

    private Long relicId;

    /** 版本号，文物内从 1 递增 */
    private Integer versionNo;

    /** 估值来源：PURCHASE/APPRAISAL/AUCTION/DONATION/OTHER */
    private String sourceType;

    /** 来源补充说明（购入渠道/拍卖场次/捐赠方等） */
    private String sourceDesc;

    /** 评估机构 */
    private String appraisalOrg;

    /** 估值金额（必须为正） */
    private BigDecimal amount;

    /** 币种：CNY/USD/EUR/JPY/GBP/HKD */
    private String currency;

    /** 有效期起 */
    private LocalDateTime effectiveFrom;

    /** 有效期止（过期由定时扫描提醒，但不冻结借展归还） */
    private LocalDateTime effectiveTo;

    /** 估值/核保凭证编号（财务可见） */
    private String voucherNo;

    /** 凭证文件名（财务可见） */
    private String voucherName;

    /** DRAFT/PENDING_APPROVAL/ACTIVE/SUPERSEDED/REJECTED */
    private String status;

    /** 修订原因（金额相对上一版本为何变更） */
    private String changeReason;

    private String submittedBy;

    private LocalDateTime submittedAt;

    /** 双人审批：初审 */
    private String firstApprover;

    private LocalDateTime firstApprovedAt;

    private String firstOpinion;

    /** 双人审批：复核（实签人必须与初审不同） */
    private String secondApprover;

    private LocalDateTime secondApprovedAt;

    private String secondOpinion;

    private String rejectedBy;

    private LocalDateTime rejectedAt;

    private String rejectReason;

    /** 生效时出具的保险批单 id（回填，不另开通道覆盖） */
    private Long endorsementId;

    private LocalDateTime activatedAt;
}
