package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 撤回后重提同一批数据：另出新批次/新编号/新时间。
 * caliberVersionId 为空时沿用原报表绑定的口径版本。
 */
@Data
public class AnnualReportResubmitRequest {

    private Long caliberVersionId;

    @NotBlank(message = "生成人不能为空")
    private String generatedBy;
}
