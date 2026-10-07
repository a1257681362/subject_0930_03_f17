package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 文物借阅申请提交（museum-10）。
 * 场景与时长必须落在文物保护等级配置的策略内；
 * 提交时校验当前展陈计划与修复状态，并固化策略快照。
 */
@Data
public class BorrowApplyRequest {

    @NotBlank(message = "申请人不能为空")
    private String applicant;

    @NotBlank(message = "借用方不能为空")
    private String borrower;

    /** 借阅场景：OUT_EXHIBITION/ACADEMIC_EXCHANGE/RESEARCH/PUBLIC_EDUCATION */
    @NotBlank(message = "借阅场景不能为空")
    private String borrowScene;

    @NotNull(message = "计划出库时间不能为空")
    private LocalDateTime planLentAt;

    @NotNull(message = "预计归还时间不能为空")
    private LocalDateTime expectedReturnAt;

    /** 借阅用途 */
    private String purpose;

    @NotNull(message = "保险金额不能为空")
    @DecimalMin(value = "0", message = "保险金额不能为负")
    private BigDecimal insuranceAmount;
}
