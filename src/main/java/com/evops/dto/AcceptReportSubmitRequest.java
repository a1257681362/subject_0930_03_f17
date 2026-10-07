package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 修复师提交成果报告：指定馆内专家与保管员两类确认人，
 * 验收清单按文物材质 + 本次工艺从配置模板生成。
 */
@Data
public class AcceptReportSubmitRequest {

    /** 修复工艺（与文物材质一起决定验收清单模板） */
    @NotBlank(message = "修复工艺不能为空")
    private String craft;

    /** 成果摘要 */
    @NotBlank(message = "成果摘要不能为空")
    private String summary;

    /** 指定确认人：馆内专家 */
    @NotBlank(message = "馆内专家确认人不能为空")
    private String expertName;

    /** 指定确认人：保管员 */
    @NotBlank(message = "保管员确认人不能为空")
    private String custodianName;

    /** 提交人（修复师） */
    @NotBlank(message = "提交人不能为空")
    private String submittedBy;
}
