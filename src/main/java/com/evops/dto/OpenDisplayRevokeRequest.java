package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 撤销开放展示标记：只置 REVOKED 留痕，原记录保留。
 */
@Data
public class OpenDisplayRevokeRequest {

    @NotBlank(message = "撤销原因不能为空")
    private String reason;

    /** 操作人 */
    @NotBlank(message = "操作人不能为空")
    private String operator;
}
