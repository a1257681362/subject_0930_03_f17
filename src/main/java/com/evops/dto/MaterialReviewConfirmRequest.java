package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 用量差异复核确认：记录复核人与意见，明细行的原操作者保持不变。
 */
@Data
public class MaterialReviewConfirmRequest {

    @NotBlank(message = "复核人不能为空")
    private String confirmedBy;

    private String note;
}
