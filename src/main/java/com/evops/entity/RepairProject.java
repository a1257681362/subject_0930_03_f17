package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 修复项目：记录委托人、修复方案，串联阶段结果与验收。
 * 状态链路：PLANNED -> REPAIRING -> REPAIRED -> ACCEPTED；
 * PLANNED/REPAIRING 可 CANCELLED，作废不删记录。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_repair_project")
public class RepairProject extends BaseEntity {

    /** 项目编号，全局唯一 */
    private String projectNo;

    private Long relicId;

    /** 委托人 */
    private String client;

    /** 委托人联系方式 */
    private String clientContact;

    /** 修复方案 */
    private String plan;

    private LocalDate startDate;

    private LocalDate expectedEndDate;

    private LocalDate finishDate;

    /** PLANNED / REPAIRING / REPAIRED / ACCEPTED / CANCELLED */
    private String status;

    private String cancelReason;

    private LocalDateTime cancelledAt;
}
