package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 工时明细行：一次作业的起止时间、工序与占用设备。
 * 起止时间原样落库（可跨天），跨天作业在统计口径上按自然日拆分，
 * 不拆改原始行。行随工时单版本封存，永不删改。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_repair_timesheet_line")
public class RepairTimesheetLine extends BaseEntity {

    private Long timesheetId;

    /** 行号，工时单内从 1 递增 */
    private Integer lineNo;

    /** 工序（如 清洗/矫形/补配/封护） */
    private String workStep;

    /** 占用设备 */
    private Long equipmentId;

    private LocalDateTime startTime;

    private LocalDateTime endTime;
}
