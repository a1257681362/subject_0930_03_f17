package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 保管员复核：
 * approved=true 且已有处置人与现场照片时关闭事件（CLOSED）；
 * approved=false 退回处置中（HANDLING），事件停留待处理，必须重新处置后再复核。
 */
@Data
public class EventReviewRequest {

    @NotBlank(message = "复核人不能为空")
    private String reviewBy;

    @NotNull(message = "复核结论不能为空")
    private Boolean approved;

    private String reviewRemark;

    /** 复核通过关闭时的关闭说明 */
    private String closeRemark;
}
