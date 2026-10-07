package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 影像/批次作废请求。作废只置状态并登记原因，不删除原文件与引用。
 */
@Data
public class VoidReasonRequest {

    @NotBlank(message = "作废原因不能为空")
    private String reason;

    /** 操作人（留痕用，可空则记系统用户） */
    private String operator;
}
