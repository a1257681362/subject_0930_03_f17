package com.evops.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 工时填报请求：修复师按工单提交一个工时单版本（含若干明细行）。
 * 同一工单同时只允许一个待审核版本；被退回后补充只能另开新版本。
 */
@Data
public class TimesheetSubmitRequest {

    /** 填报修复师 */
    @NotBlank(message = "修复师不能为空")
    private String restorer;

    /** 提交人 */
    @NotBlank(message = "提交人不能为空")
    private String submittedBy;

    @NotEmpty(message = "工时明细不能为空")
    @Valid
    private List<TimesheetLineRequest> lines;
}
