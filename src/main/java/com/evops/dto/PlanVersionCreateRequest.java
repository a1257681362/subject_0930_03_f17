package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 提交修复方案新版本：指定三类应签人，会签顺序由文物风险等级配置决定。
 */
@Data
public class PlanVersionCreateRequest {

    @NotBlank(message = "方案内容不能为空")
    private String planContent;

    /** 指定应签人：馆内专家 */
    @NotBlank(message = "馆内专家应签人不能为空")
    private String internalExpert;

    /** 指定应签人：外部专家 */
    @NotBlank(message = "外部专家应签人不能为空")
    private String externalExpert;

    /** 指定应签人：保管人员 */
    @NotBlank(message = "保管人员应签人不能为空")
    private String custodian;

    /** 提交人 */
    @NotBlank(message = "提交人不能为空")
    private String submittedBy;
}
