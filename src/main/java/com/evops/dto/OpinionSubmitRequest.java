package com.evops.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import java.util.List;

/**
 * 提交鉴定意见（初鉴 INITIAL / 复鉴 REVIEW）。
 * publish=false 仅存为未发布草稿（可由专家本人撤回）；
 * publish=true 直接发布——发布后原文锁定，只能追加更正。
 */
@Data
public class OpinionSubmitRequest {

    /** INITIAL 初鉴 / REVIEW 复鉴 */
    @NotBlank(message = "鉴定轮次不能为空（INITIAL/REVIEW）")
    private String stage;

    @NotBlank(message = "鉴定专家不能为空")
    private String expertName;

    /** 专家职称/单位 */
    private String expertTitle;

    @NotBlank(message = "鉴定依据不能为空")
    private String basis;

    @NotBlank(message = "置信说明不能为空")
    private String confidenceNote;

    /** AUTHENTIC / COUNTERFEIT / PARTIAL / INCONCLUSIVE */
    @NotBlank(message = "结论码不能为空")
    private String conclusionCode;

    @NotBlank(message = "结论文本不能为空")
    private String conclusionText;

    @NotBlank(message = "提交人不能为空")
    private String submittedBy;

    /** 是否直接发布；默认 false 存草稿 */
    private boolean publish;

    @Valid
    private List<AppraisalAttachmentItem> attachments;
}
