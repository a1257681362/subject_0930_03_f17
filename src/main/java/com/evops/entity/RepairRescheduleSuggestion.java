package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 改期建议：设备故障时按受影响工时行自动生成的建议快照。
 * 固化原时段与建议时段；处理（采纳/忽略）只改建议自身状态，
 * 不回写工时明细行——原排程的变更只能走工时单新版本。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_repair_reschedule_suggestion")
public class RepairRescheduleSuggestion extends BaseEntity {

    private Long faultId;

    /** 受影响的工时明细行 */
    private Long timesheetLineId;

    private Long projectId;

    private Long equipmentId;

    /** 原排程时段快照 */
    private LocalDateTime originStart;

    private LocalDateTime originEnd;

    /** 建议时段（起点=预计恢复时刻，时长与原行一致） */
    private LocalDateTime suggestedStart;

    private LocalDateTime suggestedEnd;

    /** PENDING / ADOPTED / DISMISSED */
    private String status;

    private String handledBy;

    private LocalDateTime handledAt;
}
