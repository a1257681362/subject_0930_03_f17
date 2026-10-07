package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 发布 / 撤回未发布意见时的操作人确认。
 * 撤回与发布只能由意见专家本人执行，他人不可代办。
 */
@Data
public class OpinionOperatorRequest {

    @NotBlank(message = "操作人不能为空")
    private String operatorName;

    /** 撤回原因（撤回时必填，由服务端校验） */
    private String reason;
}
