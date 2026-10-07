package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 修复工时单版本：修复师按工单（修复项目）填报工时，每次填报形成一个新版本。
 * 状态链路：SUBMITTED -> APPROVED / RETURNED。
 * 退回的版本封存只读，补充只能另开新版本；SUBMITTED/APPROVED 版本的明细行
 * 参与设备占用排程，RETURNED 版本不再占用设备。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_repair_timesheet")
public class RepairTimesheet extends BaseEntity {

    /** 工单（修复项目） */
    private Long projectId;

    /** 版本号，工单内从 1 递增 */
    private Integer versionNo;

    /** 填报修复师 */
    private String restorer;

    /** SUBMITTED / APPROVED / RETURNED */
    private String status;

    private String submittedBy;

    private LocalDateTime submittedAt;

    /** 审核负责人 */
    private String reviewedBy;

    private LocalDateTime reviewedAt;

    /** 审核意见（退回必填） */
    private String reviewOpinion;
}
