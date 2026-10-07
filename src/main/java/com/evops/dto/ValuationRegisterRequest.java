package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 文物保险估值登记：按文物新增一个估值版本（草稿）。
 *
 * <p>首次登记（文物尚无 ACTIVE 估值）出具 INITIAL 批单；
 * 文物已有 ACTIVE 估值时本请求属于金额修订，{@code changeReason} 必填，
 * 审批通过后出具 ADJUST 批单。{@code submitNow=true} 登记后直接提交双人审批。</p>
 */
@Data
public class ValuationRegisterRequest {

    @NotNull(message = "文物 id 不能为空")
    private Long relicId;

    /** 估值来源：PURCHASE/APPRAISAL/AUCTION/DONATION/OTHER */
    @NotBlank(message = "估值来源不能为空")
    private String sourceType;

    /** 来源补充说明 */
    private String sourceDesc;

    @NotBlank(message = "评估机构不能为空")
    private String appraisalOrg;

    @NotNull(message = "估值金额不能为空")
    @DecimalMin(value = "0.01", message = "估值金额必须为正")
    private BigDecimal amount;

    /** 币种：CNY/USD/EUR/JPY/GBP/HKD */
    @NotBlank(message = "币种不能为空")
    private String currency;

    @NotNull(message = "有效期起不能为空")
    private LocalDateTime effectiveFrom;

    @NotNull(message = "有效期止不能为空")
    private LocalDateTime effectiveTo;

    /** 估值/核保凭证编号 */
    private String voucherNo;

    /** 凭证文件名 */
    private String voucherName;

    /** 修订原因（已有 ACTIVE 估值再次登记时必填） */
    private String changeReason;

    @NotBlank(message = "登记/提交人不能为空")
    private String submittedBy;

    /** 是否登记后直接提交双人审批，默认 false 仅存草稿 */
    private Boolean submitNow;
}
