package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 借展协议版本：借展方、保险金额、运输路线、归还日期随版本固化。
 * 协议变更只追加新版本；新版本签署后旧版本置 SUPERSEDED，已签内容永不改写。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_loan_agreement_version")
public class LoanAgreementVersion extends BaseEntity {

    private Long agreementId;

    /** 版本号，协议内从 1 递增 */
    private Integer versionNo;

    /** 借展方 */
    private String borrower;

    /** 保险金额 */
    private java.math.BigDecimal insuranceAmount;

    /** 运输路线 */
    private String transportRoute;

    /** 约定归还日期 */
    private LocalDate returnDate;

    /** 其他协议条款 */
    private String terms;

    /** DRAFT / SIGNED / SUPERSEDED */
    private String status;

    /** 变更原因（v1 为初始登记） */
    private String changeReason;

    private String signedBy;

    private LocalDateTime signedAt;
}
