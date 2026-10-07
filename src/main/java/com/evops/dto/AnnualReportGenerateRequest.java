package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 生成（归档）年度报表：每次生成一条不可变快照；caliberVersionId 为空时
 * 使用当前 ACTIVE 口径版本。撤回后重提应走 /annual-reports/{id}/resubmit。
 */
@Data
public class AnnualReportGenerateRequest {

    @NotNull(message = "统计年度不能为空")
    @Min(value = 1900, message = "统计年度不合法")
    @Max(value = 2999, message = "统计年度不合法")
    private Integer year;

    /** 指定口径版本（为空取当前生效版本；报表绑定后不随后续版本改变） */
    private Long caliberVersionId;

    /** 馆区范围（为空=全馆区归档；导出时仍按导出人馆区裁剪） */
    private String campusScope;

    @NotBlank(message = "生成人不能为空")
    private String generatedBy;
}
