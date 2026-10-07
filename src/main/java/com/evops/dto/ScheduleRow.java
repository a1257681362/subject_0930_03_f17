package com.evops.dto;

import lombok.Data;

/**
 * 修复工时与设备占用排程的排程行：页面列表、CSV 导出、后台统计
 * 三个口径共用同一份行数据（同一查询方法产出），保证同口径。
 */
@Data
public class ScheduleRow {

    private Long lineId;
    private Long timesheetId;
    private Integer versionNo;
    /** 工时单版本状态：SUBMITTED / APPROVED */
    private String timesheetStatus;
    private String restorer;

    private Long projectId;
    private String projectNo;
    private String projectStatus;

    private Long relicId;
    private String relicNo;
    private String relicName;
    /** 文物档案状态：ACTIVE / REVOKED（置为无效后仅 includeRevoked=true 才出现） */
    private String relicStatus;

    private String workStep;

    private Long equipmentId;
    private String equipmentNo;
    private String equipmentName;
    private String equipmentStatus;

    private java.time.LocalDateTime startTime;
    private java.time.LocalDateTime endTime;

    /** 时长（分钟） */
    private Long minutes;
}
