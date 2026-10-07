package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 修复阶段结果：项目每完成一个阶段登记一行，stageNo 在项目内递增唯一。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_repair_stage")
public class RepairStage extends BaseEntity {

    private Long projectId;

    /** 阶段序号（项目内从 1 递增） */
    private Integer stageNo;

    private String stageName;

    /** 阶段结果记录 */
    private String stageResult;

    /** 操作人 */
    private String operator;

    private LocalDateTime recordedAt;
}
