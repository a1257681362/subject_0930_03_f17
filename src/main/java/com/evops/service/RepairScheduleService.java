package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.dto.ScheduleFilter;
import com.evops.dto.ScheduleRow;
import com.evops.entity.Relic;
import com.evops.entity.RepairEquipment;
import com.evops.entity.RepairProject;
import com.evops.entity.RepairTimesheet;
import com.evops.entity.RepairTimesheetLine;
import com.evops.mapper.RelicMapper;
import com.evops.mapper.RepairEquipmentMapper;
import com.evops.mapper.RepairProjectMapper;
import com.evops.mapper.RepairTimesheetLineMapper;
import com.evops.mapper.RepairTimesheetMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.evops.constant.MuseumConstants.*;

/**
 * 修复工时与设备占用排程的统一查询口径。
 *
 * <p>页面列表、CSV 导出、后台按日统计三个出口共用 {@link #query(ScheduleFilter)}
 * 产出的同一份行数据，保证「置为无效文物后查排程」等场景三端同口径：</p>
 * <ul>
 *   <li>占用口径：SUBMITTED / APPROVED 工时单版本的明细行参与排程；
 *       RETURNED 版本封存只读，不再占用设备。</li>
 *   <li>无效文物口径：文物 REVOKED 后其行默认不计入；includeRevoked=true 时
 *       行保留并以 relicStatus=REVOKED 标明。页面/导出/统计同一开关。</li>
 *   <li>跨天口径：原始行不拆库，统计时按自然日拆分到每天。</li>
 * </ul>
 */
@Service
public class RepairScheduleService {

    private static final DateTimeFormatter CSV_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final RepairTimesheetMapper timesheetMapper;
    private final RepairTimesheetLineMapper lineMapper;
    private final RepairProjectMapper projectMapper;
    private final RelicMapper relicMapper;
    private final RepairEquipmentMapper equipmentMapper;

    public RepairScheduleService(RepairTimesheetMapper timesheetMapper,
                                 RepairTimesheetLineMapper lineMapper,
                                 RepairProjectMapper projectMapper,
                                 RelicMapper relicMapper,
                                 RepairEquipmentMapper equipmentMapper) {
        this.timesheetMapper = timesheetMapper;
        this.lineMapper = lineMapper;
        this.projectMapper = projectMapper;
        this.relicMapper = relicMapper;
        this.equipmentMapper = equipmentMapper;
    }

    /**
     * 排程行统一查询：页面/导出/统计/互斥判定/故障建议全部走这里。
     */
    public List<ScheduleRow> query(ScheduleFilter filter) {
        // 有效占用版本：待审核 + 审核通过；已退回版本封存，不再占用设备
        List<RepairTimesheet> sheets = timesheetMapper.selectList(
                new LambdaQueryWrapper<RepairTimesheet>()
                        .in(RepairTimesheet::getStatus, TS_SUBMITTED, TS_APPROVED));
        Map<Long, RepairTimesheet> sheetById = new HashMap<>();
        for (RepairTimesheet sheet : sheets) {
            sheetById.put(sheet.getId(), sheet);
        }

        Map<Long, RepairProject> projectCache = new HashMap<>();
        Map<Long, Relic> relicCache = new HashMap<>();
        Map<Long, RepairEquipment> equipmentCache = new HashMap<>();

        List<RepairTimesheetLine> lines = lineMapper.selectList(
                new LambdaQueryWrapper<RepairTimesheetLine>()
                        .eq(filter.getEquipmentId() != null,
                                RepairTimesheetLine::getEquipmentId, filter.getEquipmentId())
                        .lt(filter.getTo() != null, RepairTimesheetLine::getStartTime, filter.getTo())
                        .gt(filter.getFrom() != null, RepairTimesheetLine::getEndTime, filter.getFrom())
                        .orderByAsc(RepairTimesheetLine::getStartTime)
                        .orderByAsc(RepairTimesheetLine::getId));

        List<ScheduleRow> rows = new ArrayList<>();
        for (RepairTimesheetLine line : lines) {
            RepairTimesheet sheet = sheetById.get(line.getTimesheetId());
            if (sheet == null) {
                continue; // 已退回/不存在版本：不参与排程
            }
            if (filter.getProjectId() != null && !filter.getProjectId().equals(sheet.getProjectId())) {
                continue;
            }
            RepairProject project = projectCache.computeIfAbsent(sheet.getProjectId(),
                    projectMapper::selectById);
            if (project == null) {
                continue;
            }
            if (filter.isOnlyRepairingProjects() && !PROJECT_REPAIRING.equals(project.getStatus())) {
                continue;
            }
            Relic relic = relicCache.computeIfAbsent(project.getRelicId(), relicMapper::selectById);
            if (relic == null) {
                continue;
            }
            // 无效文物口径：默认剔除；includeRevoked=true 时保留并标明 REVOKED
            if (!filter.isIncludeRevoked() && REVOKED.equals(relic.getStatus())) {
                continue;
            }
            RepairEquipment equipment = equipmentCache.computeIfAbsent(line.getEquipmentId(),
                    equipmentMapper::selectById);

            ScheduleRow row = new ScheduleRow();
            row.setLineId(line.getId());
            row.setTimesheetId(sheet.getId());
            row.setVersionNo(sheet.getVersionNo());
            row.setTimesheetStatus(sheet.getStatus());
            row.setRestorer(sheet.getRestorer());
            row.setProjectId(project.getId());
            row.setProjectNo(project.getProjectNo());
            row.setProjectStatus(project.getStatus());
            row.setRelicId(relic.getId());
            row.setRelicNo(relic.getRelicNo());
            row.setRelicName(relic.getName());
            row.setRelicStatus(relic.getStatus());
            row.setWorkStep(line.getWorkStep());
            row.setEquipmentId(line.getEquipmentId());
            if (equipment != null) {
                row.setEquipmentNo(equipment.getEquipmentNo());
                row.setEquipmentName(equipment.getName());
                row.setEquipmentStatus(equipment.getStatus());
            }
            row.setStartTime(line.getStartTime());
            row.setEndTime(line.getEndTime());
            row.setMinutes(Duration.between(line.getStartTime(), line.getEndTime()).toMinutes());
            rows.add(row);
        }
        return rows;
    }

    /**
     * 后台统计：与页面/导出同一行集，跨天作业按自然日拆分，
     * 按（自然日 + 工单 + 设备）汇总工时分钟数。
     */
    public Map<String, Object> summary(ScheduleFilter filter) {
        List<ScheduleRow> rows = query(filter);
        // 按 日期+工单+设备 聚合；LinkedHashMap 保持先按日期再按出现的稳定顺序
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        long totalMinutes = 0;
        for (ScheduleRow row : rows) {
            LocalDate day = row.getStartTime().toLocalDate();
            LocalDate endDay = row.getEndTime().toLocalDate();
            while (!day.isAfter(endDay)) {
                LocalDateTime dayStart = day.atStartOfDay();
                LocalDateTime dayEnd = day.plusDays(1).atStartOfDay();
                LocalDateTime segStart = row.getStartTime().isAfter(dayStart)
                        ? row.getStartTime() : dayStart;
                LocalDateTime segEnd = row.getEndTime().isBefore(dayEnd)
                        ? row.getEndTime() : dayEnd;
                if (segStart.isBefore(segEnd)) {
                    long minutes = Duration.between(segStart, segEnd).toMinutes();
                    final LocalDate workDay = day;
                    String key = workDay + "|" + row.getProjectId() + "|" + row.getEquipmentId();
                    Map<String, Object> bucket = grouped.computeIfAbsent(key, k -> {
                        Map<String, Object> b = new LinkedHashMap<>();
                        b.put("workDate", workDay.toString());
                        b.put("projectId", row.getProjectId());
                        b.put("projectNo", row.getProjectNo());
                        b.put("relicId", row.getRelicId());
                        b.put("relicNo", row.getRelicNo());
                        b.put("relicName", row.getRelicName());
                        b.put("relicStatus", row.getRelicStatus());
                        b.put("equipmentId", row.getEquipmentId());
                        b.put("equipmentNo", row.getEquipmentNo());
                        b.put("equipmentName", row.getEquipmentName());
                        b.put("minutes", 0L);
                        return b;
                    });
                    bucket.put("minutes", (Long) bucket.get("minutes") + minutes);
                    totalMinutes += minutes;
                }
                day = day.plusDays(1);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", new ArrayList<>(grouped.values()));
        result.put("totalMinutes", totalMinutes);
        return result;
    }

    /**
     * CSV 导出：与页面查询同一行集同一顺序，列口径固定。
     * 带 BOM，便于 Excel 直接打开中文。
     */
    public String exportCsv(ScheduleFilter filter) {
        List<ScheduleRow> rows = query(filter);
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append("工时行ID,项目编号,项目状态,文物编号,文物名称,文物状态,工序,设备编号,设备名称,"
                + "开始时间,结束时间,时长(分钟),工时单版本,工时单状态,修复师\n");
        for (ScheduleRow row : rows) {
            csv.append(row.getLineId()).append(',')
                    .append(csvCell(row.getProjectNo())).append(',')
                    .append(csvCell(row.getProjectStatus())).append(',')
                    .append(csvCell(row.getRelicNo())).append(',')
                    .append(csvCell(row.getRelicName())).append(',')
                    .append(csvCell(row.getRelicStatus())).append(',')
                    .append(csvCell(row.getWorkStep())).append(',')
                    .append(csvCell(row.getEquipmentNo())).append(',')
                    .append(csvCell(row.getEquipmentName())).append(',')
                    .append(row.getStartTime().format(CSV_TIME)).append(',')
                    .append(row.getEndTime().format(CSV_TIME)).append(',')
                    .append(row.getMinutes()).append(',')
                    .append('V').append(row.getVersionNo()).append(',')
                    .append(csvCell(row.getTimesheetStatus())).append(',')
                    .append(csvCell(row.getRestorer()))
                    .append('\n');
        }
        return csv.toString();
    }

    /**
     * 设备占用互斥判定：同一设备、时段重叠、且属于<b>另一个修复中工单</b>的
     * 有效（SUBMITTED/APPROVED）工时行数。调用方须已持有设备行锁。
     */
    public long countEquipmentOverlap(Long equipmentId, LocalDateTime start, LocalDateTime end,
                                      Long excludeProjectId) {
        ScheduleFilter filter = new ScheduleFilter();
        filter.setEquipmentId(equipmentId);
        filter.setFrom(start);
        filter.setTo(end);
        filter.setOnlyRepairingProjects(true);
        long count = 0;
        for (ScheduleRow row : query(filter)) {
            if (excludeProjectId != null && excludeProjectId.equals(row.getProjectId())) {
                continue; // 同一工单的行不构成“两个工单”冲突
            }
            count++;
        }
        return count;
    }

    private String csvCell(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
