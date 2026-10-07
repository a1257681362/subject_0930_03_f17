package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/** 补充记录：已关闭任务唯一允许的新增（原结果不重写） */
@Data
public class InvSupplementRequest {

    @NotBlank(message = "补充说明不能为空")
    private String content;

    @NotBlank(message = "记录人不能为空")
    private String operatorName;
}
