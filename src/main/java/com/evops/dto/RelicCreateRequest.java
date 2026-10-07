package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class RelicCreateRequest {

    @NotBlank(message = "文物编号不能为空")
    private String relicNo;

    @NotBlank(message = "文物名称不能为空")
    private String name;

    /** 品类 */
    private String category;

    @NotBlank(message = "材质不能为空")
    private String material;

    @NotBlank(message = "年代不能为空")
    private String era;

    @NotBlank(message = "来源不能为空")
    private String source;

    /** 保存等级 ONE/TWO/THREE */
    @NotBlank(message = "保存等级不能为空")
    private String preservationGrade;

    /** 所属馆区编码，留空按主馆区 MAIN；借阅审批的保险资料按馆区隔离 */
    private String campusCode;

    private String description;
}
