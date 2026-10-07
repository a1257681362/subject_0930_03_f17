package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 按保护等级配置的借阅策略（museum-10）：一等级一行，维护当前值。
 * allowedScenes/approvalRoles 为逗号分隔有序码值；
 * 申请提交时把策略固化到申请与借阅快照，之后策略调整不影响在途审批。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_borrow_grade_policy")
public class BorrowGradePolicy extends BaseEntity {

    /** 保护等级 ONE/TWO/THREE */
    private String preservationGrade;

    /** 可借阅场景，逗号分隔：OUT_EXHIBITION/ACADEMIC_EXCHANGE/RESEARCH/PUBLIC_EDUCATION */
    private String allowedScenes;

    /** 最高借阅时长（自然日） */
    private Integer maxDurationDays;

    /** 有序审批角色，逗号分隔：BORROW_DEPT/COLLECTION_LEADER/DIRECTOR */
    private String approvalRoles;

    /** 高风险等级是否必须追加馆长签名 1/0 */
    private Integer directorSignRequired;
}
