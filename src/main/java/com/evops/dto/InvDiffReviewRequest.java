package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/** 差异单复核：通过则复核完成；退回须填意见 */
@Data
public class InvDiffReviewRequest {

    @NotBlank(message = "复核人不能为空")
    private String reviewedBy;

    @NotNull(message = "复核结论不能为空")
    private Boolean approved;

    /** 复核意见（退回必填） */
    private String note;
}
