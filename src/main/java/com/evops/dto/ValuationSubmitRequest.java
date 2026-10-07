package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 估值版本提交审批：仅 DRAFT 可提交，提交后进入 PENDING_APPROVAL，
 * 等待 FIRST 初审 + SECOND 复核两名不同人员双人审批。
 */
@Data
public class ValuationSubmitRequest {

    @NotBlank(message = "提交人不能为空")
    private String submittedBy;

    /** 修订原因补充（草稿登记时未填可在此补齐） */
    private String changeReason;
}
