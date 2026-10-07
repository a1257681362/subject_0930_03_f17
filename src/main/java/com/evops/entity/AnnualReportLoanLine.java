package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 年度报表借展明细快照行：一次借展协议版本签署一行。
 * 协议变更重新签署各算一次借展（museum-6 口径）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_annual_report_loan_line")
public class AnnualReportLoanLine extends BaseEntity {

    private Long reportId;

    private Long agreementId;

    private String agreementNo;

    private Long agreementVersionId;

    private Integer versionNo;

    private Long relicId;

    private String relicNo;

    private String relicName;

    private String campusCode;

    private String secretLevel;

    private String borrower;

    private String signedBy;

    private LocalDateTime signedAt;

    private LocalDateTime createdAt;
}
