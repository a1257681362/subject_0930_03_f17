package com.evops.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import java.util.List;

/**
 * 仲裁定稿：
 * ADOPT_INITIAL 采初鉴 / ADOPT_REVIEW 采复鉴（结论自对应意见带入）；
 * ADJUSTED 综合调整，必须显式给出最终结论码、文本、依据与置信说明。
 */
@Data
public class ArbitrationConcludeRequest {

    @NotBlank(message = "仲裁人不能为空")
    private String arbiterName;

    /** ADOPT_INITIAL / ADOPT_REVIEW / ADJUSTED */
    @NotBlank(message = "仲裁决定不能为空")
    private String finalDecision;

    /** ADJUSTED 时必填：AUTHENTIC/COUNTERFEIT/PARTIAL/INCONCLUSIVE */
    private String finalConclusionCode;

    /** ADJUSTED 时必填 */
    private String finalConclusionText;

    /** ADJUSTED 时必填 */
    private String finalBasis;

    /** ADJUSTED 时必填：仲裁置信说明 */
    private String confidenceNote;

    @Valid
    private List<AppraisalAttachmentItem> attachments;
}
