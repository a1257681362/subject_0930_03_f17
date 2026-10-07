package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class RevokeRequest {

    @NotBlank(message = "撤销原因不能为空")
    private String reason;
}
