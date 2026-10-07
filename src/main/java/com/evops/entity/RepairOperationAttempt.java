package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 修复操作并发尝试审计：同一项目同一操作并发发起时，每次尝试都落一行。
 * EFFECTIVE 为最终生效的一次；REJECTED 记录被拒原因，并指向胜出的生效尝试，
 * 明确"谁生效、谁被拒绝"。request_no 稀疏唯一：客户端重试/补传复用流水号时
 * 命中同一次尝试，绝不重复执行。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_repair_operation_attempt")
public class RepairOperationAttempt extends BaseEntity {

    private Long projectId;

    /** START / STAGE / FINISH / ACCEPT / CANCEL */
    private String operation;

    /** 客户端请求流水号，可空；非空时全表唯一 */
    private String requestNo;

    private String operatorName;

    /** EFFECTIVE / REJECTED */
    private String decision;

    private String rejectReason;

    /** 被拒绝时指向最终生效的那次尝试 */
    private Long effectiveAttemptId;

    /** 生效尝试产出的业务行 id（阶段/验收等） */
    private Long bizId;

    private LocalDateTime occurredAt;
}
