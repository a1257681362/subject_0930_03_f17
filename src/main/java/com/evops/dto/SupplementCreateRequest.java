package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 开立补修单：开放展示后发现问题时登记，原开放记录保留不动。
 */
@Data
public class SupplementCreateRequest {

    /** 开放后发现的问题描述 */
    @NotBlank(message = "问题描述不能为空")
    private String issue;

    /** 登记人 */
    @NotBlank(message = "登记人不能为空")
    private String createdBy;
}
