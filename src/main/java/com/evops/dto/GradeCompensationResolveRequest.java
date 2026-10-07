package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 保护等级补偿闭环确认（museum-10）。
 * 存在成对借阅补偿时，必须先闭环借阅补偿；可给出最终等级再次更正文物档案。
 */
@Data
public class GradeCompensationResolveRequest {

    @NotBlank(message = "确认人不能为空")
    private String resolvedBy;

    /** 最终保护等级；留空则按验收时调整后等级确认 */
    private String resolvedGrade;

    @NotBlank(message = "闭环说明不能为空")
    private String note;
}
