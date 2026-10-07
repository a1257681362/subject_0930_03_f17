package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 借展协议主单：一次借展一行。
 * DRAFT 待签 -> SIGNED 已签 -> IN_TRANSIT 押运中 -> RETURNED 已归还验收；
 * CANCELLED 为旁路终态。不做物理删除。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_loan_agreement")
public class LoanAgreement extends BaseEntity {

    /** 协议单号 */
    private String agreementNo;

    private Long relicId;

    /** 当前生效/在签版本 id */
    private Long currentVersionId;

    /** 借展用途 */
    private String purpose;

    /** DRAFT / SIGNED / IN_TRANSIT / RETURNED / CANCELLED */
    private String status;
}
