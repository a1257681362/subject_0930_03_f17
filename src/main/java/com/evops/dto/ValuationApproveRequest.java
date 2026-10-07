package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 估值金额修订双人审批签署。
 *
 * <p>FIRST 初审与 SECOND 复核必须由两名不同实签人完成，且初审不能由
 * 提交人本人代签；任一环节 REJECT 版本即驳回（REJECTED，留痕不删档），
 * 两签全部 APPROVE 新版本才生效并出具保险批单。</p>
 */
@Data
public class ValuationApproveRequest {

    /** 签署位次：FIRST 初审 / SECOND 复核 */
    @NotBlank(message = "审批位次不能为空")
    private String step;

    /** 决定：APPROVE 通过 / REJECT 驳回 */
    @NotBlank(message = "审批决定不能为空")
    private String decision;

    @NotBlank(message = "审批人不能为空")
    private String approver;

    /** 通过意见 */
    private String opinion;

    /** 驳回原因（decision=REJECT 时必填） */
    private String rejectReason;
}
