package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 工时审核请求：负责人对待审核版本作通过/退回决定。
 * 退回必填审核意见；退回后版本封存只读，只能补充新版本。
 */
@Data
public class TimesheetReviewRequest {

    /** 审核决定：APPROVE 通过 / RETURN 退回 */
    @NotBlank(message = "审核决定不能为空")
    private String decision;

    /** 审核负责人 */
    @NotBlank(message = "审核人不能为空")
    private String reviewedBy;

    /** 审核意见（退回时必填） */
    private String opinion;
}
