package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.util.List;

/**
 * 按保护等级调整借阅策略（museum-10）：只维护当前值。
 * 已提交申请固化的策略快照不受影响。
 */
@Data
public class BorrowPolicyUpdateRequest {

    /** 可借阅场景码值（有序去重由服务端处理） */
    @NotEmpty(message = "可借阅场景至少配置一个")
    private List<String> allowedScenes;

    @NotNull(message = "最高借阅时长不能为空")
    @Min(value = 1, message = "最高借阅时长必须为正整数")
    private Integer maxDurationDays;

    /** 有序审批角色码值 */
    @NotEmpty(message = "审批角色至少配置一个")
    private List<String> approvalRoles;

    /** 是否必须追加馆长签名（高风险等级） */
    @NotNull(message = "馆长签名要求不能为空")
    private Boolean directorSignRequired;

    @NotNull(message = "更新人不能为空")
    private String updatedBy;
}
