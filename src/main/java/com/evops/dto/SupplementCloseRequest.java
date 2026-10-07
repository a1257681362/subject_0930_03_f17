package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 补修单闭环：登记处置结果，记录保留。
 */
@Data
public class SupplementCloseRequest {

    @NotBlank(message = "闭环人不能为空")
    private String closedBy;

    /** 处置结果说明 */
    @NotBlank(message = "处置结果说明不能为空")
    private String closeNote;
}
