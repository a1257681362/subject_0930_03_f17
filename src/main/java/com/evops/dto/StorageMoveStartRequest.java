package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 移动开工：登记实际操作人员。
 */
@Data
public class StorageMoveStartRequest {

    @NotBlank(message = "操作人员不能为空")
    private String operator;
}
