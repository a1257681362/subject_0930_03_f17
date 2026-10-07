package com.evops.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import java.util.List;

/**
 * 对已发布意见追加更正：只追加，不改写意见原文。
 */
@Data
public class OpinionCorrectionRequest {

    @NotBlank(message = "更正内容不能为空")
    private String content;

    /** 更正人；必须是原意见专家本人 */
    @NotBlank(message = "更正人不能为空")
    private String correctedBy;

    @Valid
    private List<AppraisalAttachmentItem> attachments;
}
