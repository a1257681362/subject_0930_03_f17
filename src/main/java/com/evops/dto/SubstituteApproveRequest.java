package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 临时替代包装审批：通过后装箱单进入外部依赖确认；驳回必填意见且装箱单终态 REJECTED。
 */
@Data
public class SubstituteApproveRequest {

    @NotBlank(message = "审批人不能为空")
    private String approver;

    @NotNull(message = "审批结论不能为空")
    private Boolean approved;

    /** 驳回时必填；通过时可选 */
    private String opinion;
}
