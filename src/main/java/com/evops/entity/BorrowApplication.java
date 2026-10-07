package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 文物借阅申请（museum-10）。
 * 提交时固化等级/馆区/审批角色/馆长签名要求等策略快照；
 * 状态链路：PENDING 审批中 -> APPROVED 已批准待出库 -> BORROWED 已出库 -> RETURNED 已归还；
 * 终态另有 REJECTED 驳回 / CANCELLED 撤销。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_borrow_application")
public class BorrowApplication extends BaseEntity {

    /** 申请单号，全库唯一 */
    private String applicationNo;

    private Long relicId;

    /** 申请人 */
    private String applicant;

    /** 借用方（单位/个人） */
    private String borrower;

    /** 借阅场景：OUT_EXHIBITION/ACADEMIC_EXCHANGE/RESEARCH/PUBLIC_EDUCATION */
    private String borrowScene;

    private LocalDateTime planLentAt;

    private LocalDateTime expectedReturnAt;

    /** 申请借阅时长（自然日），不超过等级策略上限 */
    private Integer durationDays;

    private String purpose;

    /** 保险金额（跨馆区查看脱敏） */
    private BigDecimal insuranceAmount;

    /** 文物所属馆区：保险资料按馆区隔离 */
    private String campusCode;

    /** 提交时文物保护等级快照 */
    private String preservationGrade;

    /** 提交时固化的有序审批角色 */
    private String approvalRoles;

    /** 提交时固化：高风险是否必须馆长签名 */
    private Integer directorSignRequired;

    /** PENDING/APPROVED/BORROWED/RETURNED/REJECTED/CANCELLED */
    private String status;

    private String rejectReason;

    private LocalDateTime approvedAt;

    private LocalDateTime cancelledAt;
}
