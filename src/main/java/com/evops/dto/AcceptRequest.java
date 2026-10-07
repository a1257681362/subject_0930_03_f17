package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class AcceptRequest {

    @NotBlank(message = "验收人不能为空")
    private String acceptedBy;

    /** PASS / CONDITIONAL，默认 PASS */
    private String result;

    private String opinion;

    /**
     * 附条件通过（CONDITIONAL，部分成功）时登记的验收后保护等级 ONE/TWO/THREE。
     * CONDITIONAL 必填：立即落到文物档案，并开立保护等级补偿（museum-10）。
     */
    private String gradeAfter;

    /**
     * 客户端请求流水号（museum-11）：并发/重试/补传复用，命中同一次尝试，不重复验收。
     */
    private String requestNo;
}
