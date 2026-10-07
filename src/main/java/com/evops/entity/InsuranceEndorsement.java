package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 保险批单（museum-15）：估值新版本双人审批通过生效时一并出具，出具后不可变。
 *
 * <p>INITIAL 为首次核保定价；ADJUST 为金额变更批单，固化新旧版本与金额，
 * 并由新版本行回填 valuation_version_id 关联，做到「变更必生新版本、
 * 新版本必挂保险批单」。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_insurance_endorsement")
public class InsuranceEndorsement extends BaseEntity {

    /** 批单号，全库唯一 */
    private String endorsementNo;

    private Long relicId;

    /** 本次生效的估值版本 id（一版本至多一张批单） */
    private Long valuationVersionId;

    /** INITIAL 首次核保 / ADJUST 金额变更 */
    private String endorsementType;

    private Long previousVersionId;

    private BigDecimal previousAmount;

    private String previousCurrency;

    private BigDecimal newAmount;

    private String newCurrency;

    private String changeReason;

    private String issuedBy;

    private LocalDateTime issuedAt;

    /** 出具后恒为 ISSUED，不可变 */
    private String status;
}
