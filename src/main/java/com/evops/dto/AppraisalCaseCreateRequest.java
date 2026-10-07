package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 开立文物鉴定案件：一案串联初鉴与复鉴。
 */
@Data
public class AppraisalCaseCreateRequest {

    @NotNull(message = "文物 id 不能为空")
    private Long relicId;

    /** 关联修复项目，可空（鉴定可独立发起） */
    private Long projectId;

    @NotBlank(message = "案件标题不能为空")
    private String title;

    @NotBlank(message = "开立人不能为空")
    private String openedBy;
}
