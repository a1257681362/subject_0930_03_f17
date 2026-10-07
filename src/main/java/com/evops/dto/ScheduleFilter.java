package com.evops.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 排程查询过滤条件：页面、导出、后台统计三端共用一个过滤器，
 * 保证「修复工时与设备占用排程」同口径。
 */
@Data
public class ScheduleFilter {

    private Long projectId;

    private Long equipmentId;

    /** 时段过滤（重叠即命中）：行开始 < to 且 行结束 > from */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime from;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime to;

    /**
     * 是否包含已撤销（置为无效）文物的行：默认 false——
     * 文物置为无效后其工时与占用行不再计入页面/导出/统计；
     * true 时行保留并以 relicStatus=REVOKED 标明。三端同一开关。
     */
    private boolean includeRevoked = false;

    /** 仅统计修复中工单：设备占用互斥判定与故障建议生成使用 */
    private boolean onlyRepairingProjects = false;
}
