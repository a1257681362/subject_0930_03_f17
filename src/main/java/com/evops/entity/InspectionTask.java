package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 巡检任务：按展厅生成，一轮一行。
 * ruleVersionId/No 为任务生成时生效规则版本快照——规则新版本不改写已生成/已完成巡检。
 * status: PENDING 待巡检 / DONE 已完成 / CANCELLED 已取消。
 * (hallId, roundNo) 唯一，定时与手动触发同一轮任务幂等。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_inspection_task")
public class InspectionTask extends BaseEntity {

    private String taskNo;

    private Long hallId;

    /** 展厅内轮次号，从 1 递增 */
    private Integer roundNo;

    /** 任务生成时生效规则版本快照 */
    private Long ruleVersionId;
    private String ruleVersionNo;

    /** 本轮巡检节拍（分钟，取展厅在展文物材质频率最小值） */
    private Integer intervalMin;

    /** 应采样点位数 */
    private Integer pointCount;

    private LocalDateTime plannedAt;

    /** 到点时间（plannedAt + intervalMin） */
    private LocalDateTime dueAt;

    private LocalDateTime finishedAt;

    private String inspector;

    /** PENDING / DONE / CANCELLED */
    private String status;
}
