package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 借阅审批签署（museum-10）：原申请链与归还延期重审链共用。
 * 按申请固化的角色顺序逐人签署；高风险最后一步必须为馆长签名（DIRECTOR）。
 */
@Data
public class BorrowApproveRequest {

    /** 本步签署角色：BORROW_DEPT/COLLECTION_LEADER/DIRECTOR */
    @NotBlank(message = "审批角色不能为空")
    private String approverRole;

    @NotBlank(message = "审批人不能为空")
    private String approverName;

    /** APPROVE/REJECT */
    @NotBlank(message = "审批决定不能为空")
    private String decision;

    /** 驳回意见（驳回时必填，服务端校验） */
    private String opinion;

    /** 审批人所属馆区：用于查看保险资料是否同馆区（仅留痕，不改变签署校验） */
    private String campusCode;
}
