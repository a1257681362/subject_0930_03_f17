package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/** 确认（关闭）年度报表生成失败告警 */
@Data
public class AnnualAlertAckRequest {

    @NotBlank(message = "确认人不能为空")
    private String acknowledgedBy;

    private String note;
}
