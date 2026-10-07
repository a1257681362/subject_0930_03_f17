package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 借展签约估值快照（museum-15）：协议每次签署固化一条，生成后不可变。
 *
 * <p>借展期间（押运、在展、归还验收）一律沿用签约时的估值快照：
 * 来源/评估机构/币种/金额/有效期/凭证随签约口径冻结，
 * 后续当前估值发生修订、出具批单或到期提醒，都不改写、不替换在借快照。
 * 无 ACTIVE 估值可快照时仍出快照行（金额字段留空并在 note 说明），
 * 保证「签约时无估值」这一事实本身也被固化。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_ins_valuation_snapshot")
public class InsuranceValuationSnapshot extends BaseEntity {

    /** 快照单号，全库唯一 */
    private String snapshotNo;

    private Long relicId;

    private Long agreementId;

    private String agreementNo;

    /** 本次签署的协议版本 id：同协议版本只允许一份快照 */
    private Long agreementVersionId;

    private Long valuationVersionId;

    private Integer versionNo;

    private String sourceType;

    private String sourceDesc;

    private String appraisalOrg;

    private BigDecimal amount;

    private String currency;

    private LocalDateTime effectiveFrom;

    private LocalDateTime effectiveTo;

    private String voucherNo;

    private String borrower;

    private String signedBy;

    /** 无有效估值等情形的固化说明 */
    private String note;

    private LocalDateTime generatedAt;
}
