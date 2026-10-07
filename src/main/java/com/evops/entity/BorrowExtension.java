package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 归还延期申请（museum-10）：必须重新走完整审批链
 * （审批记录 t_borrow_approval_record 以 extension_id 挂本单）。
 * status: PENDING 重新审批中 / APPROVED 通过（回写应还时间）/ REJECTED 驳回（原时间不变）。
 * seq_no 为同一借阅申请内的延期次序。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_borrow_extension")
public class BorrowExtension extends BaseEntity {

    private String extensionNo;

    private Long applicationId;

    private Integer seqNo;

    private LocalDateTime originalExpectedReturnAt;

    private LocalDateTime requestedReturnAt;

    private String reason;

    /** 延期重审沿用原申请固化的审批角色与馆长要求 */
    private String approvalRoles;

    private Integer directorSignRequired;

    /** PENDING/APPROVED/REJECTED */
    private String status;

    private String submittedBy;

    private LocalDateTime submittedAt;

    private String handledBy;

    private LocalDateTime handledAt;

    private String handleOpinion;
}
