package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 材料目录建档：颜料 / 黏合剂 / 保护剂。
 */
@Data
public class MaterialCreateRequest {

    @NotBlank(message = "材料编号不能为空")
    private String materialNo;

    @NotBlank(message = "材料名称不能为空")
    private String name;

    /** PIGMENT / ADHESIVE / PROTECTANT */
    @NotBlank(message = "材料类别不能为空")
    private String category;

    /** 计量单位，如 g / ml */
    @NotBlank(message = "计量单位不能为空")
    private String unit;

    private String spec;
}
