package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.dto.TimesheetLineRequest;
import com.evops.dto.TimesheetReviewRequest;
import com.evops.dto.TimesheetSubmitRequest;
import com.evops.entity.RepairEquipment;
import com.evops.entity.RepairProject;
import com.evops.entity.RepairTimesheet;
import com.evops.entity.RepairTimesheetLine;
import com.evops.mapper.RepairEquipmentMapper;
import com.evops.mapper.RepairProjectMapper;
import com.evops.mapper.RepairTimesheetLineMapper;
import com.evops.mapper.RepairTimesheetMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static com.evops.constant.MuseumConstants.*;

/**
 * 修复工时填报与审核：
 *   修复师按工单填报起止时间、工序和设备（每次填报 = 一个新版本）
 *   -> 负责人审核：通过 APPROVED / 退回 RETURNED
 *   -> 退回的版本封存只读，补充只能另开新版本。
 *
 * <p>设备互斥：同一设备同一时段不能被两个工单占用——提交时逐设备加行锁，
 * 在锁内复查其他修复中工单的有效（SUBMITTED/APPROVED）工时行是否时段重叠。</p>
 */
@Service
public class RepairTimesheetService {

    private final RepairTimesheetMapper timesheetMapper;
    private final RepairTimesheetLineMapper lineMapper;
    private final RepairProjectMapper projectMapper;
    private final RepairEquipmentMapper equipmentMapper;
    private final RepairScheduleService scheduleService;
    private final LedgerService ledgerService;

    public RepairTimesheetService(RepairTimesheetMapper timesheetMapper,
                                  RepairTimesheetLineMapper lineMapper,
                                  RepairProjectMapper projectMapper,
                                  RepairEquipmentMapper equipmentMapper,
                                  RepairScheduleService scheduleService,
                                  LedgerService ledgerService) {
        this.timesheetMapper = timesheetMapper;
        this.lineMapper = lineMapper;
        this.projectMapper = projectMapper;
        this.equipmentMapper = equipmentMapper;
        this.scheduleService = scheduleService;
        this.ledgerService = ledgerService;
    }

    /**
     * 填报工时：仅修复中工单可填报；同一工单同时只允许一个待审核版本。
     * 版本号工单内递增；退回后补充走这里另开新版本，旧版本保持只读。
     */
    @Transactional
    public RepairTimesheet submit(Long projectId, TimesheetSubmitRequest req) {
        RepairProject project = requireProject(projectId);
        if (!PROJECT_REPAIRING.equals(project.getStatus())) {
            throw new IllegalArgumentException("项目不在修复中，不能填报工时: " + project.getProjectNo());
        }
        Long submitting = timesheetMapper.selectCount(new LambdaQueryWrapper<RepairTimesheet>()
                .eq(RepairTimesheet::getProjectId, projectId)
                .eq(RepairTimesheet::getStatus, TS_SUBMITTED));
        if (submitting != null && submitting > 0) {
            throw new IllegalArgumentException("存在待审核的工时单版本，不能重复提交: " + project.getProjectNo());
        }

        // 明细行基本校验 + 收集涉及设备
        TreeSet<Long> equipmentIds = new TreeSet<>();
        for (TimesheetLineRequest line : req.getLines()) {
            if (!line.getStartTime().isBefore(line.getEndTime())) {
                throw new IllegalArgumentException("工时行开始时间必须早于结束时间: " + line.getWorkStep());
            }
            equipmentIds.add(line.getEquipmentId());
        }

        // 设备行锁：按设备 id 升序逐个加锁（避免死锁），把并发提交串行化后再做占用判定
        Map<Long, RepairEquipment> equipmentById = new LinkedHashMap<>();
        for (Long equipmentId : equipmentIds) {
            RepairEquipment equipment = equipmentMapper.selectById(equipmentId);
            if (equipment == null) {
                throw new IllegalArgumentException("设备不存在: id=" + equipmentId);
            }
            equipmentMapper.lockRow(equipmentId);
            equipmentById.put(equipmentId, equipmentMapper.selectById(equipmentId));
        }

        // 锁内判定：设备须在用（故障中不可排新工时），且时段不与其他工单冲突
        for (TimesheetLineRequest line : req.getLines()) {
            RepairEquipment equipment = equipmentById.get(line.getEquipmentId());
            if (!EQUIP_ACTIVE.equals(equipment.getStatus())) {
                throw new IllegalArgumentException("设备 " + equipment.getEquipmentNo()
                        + " 故障中，不能填报该时段工时，请关注改期建议");
            }
            if (scheduleService.countEquipmentOverlap(line.getEquipmentId(),
                    line.getStartTime(), line.getEndTime(), projectId) > 0) {
                throw new IllegalArgumentException("设备 " + equipment.getEquipmentNo()
                        + " 在 " + line.getStartTime() + "~" + line.getEndTime()
                        + " 已被其他工单占用，同一时段不能被两个工单占用");
            }
        }

        Integer maxNo = timesheetMapper.selectList(new LambdaQueryWrapper<RepairTimesheet>()
                        .eq(RepairTimesheet::getProjectId, projectId)
                        .orderByDesc(RepairTimesheet::getVersionNo)
                        .last("LIMIT 1"))
                .stream().findFirst().map(RepairTimesheet::getVersionNo).orElse(0);

        LocalDateTime now = LocalDateTime.now();
        RepairTimesheet sheet = new RepairTimesheet();
        sheet.setProjectId(projectId);
        sheet.setVersionNo(maxNo + 1);
        sheet.setRestorer(req.getRestorer());
        sheet.setStatus(TS_SUBMITTED);
        sheet.setSubmittedBy(req.getSubmittedBy());
        sheet.setSubmittedAt(now);
        timesheetMapper.insert(sheet);

        int lineNo = 0;
        for (TimesheetLineRequest lineReq : req.getLines()) {
            RepairTimesheetLine line = new RepairTimesheetLine();
            line.setTimesheetId(sheet.getId());
            line.setLineNo(++lineNo);
            line.setWorkStep(lineReq.getWorkStep());
            line.setEquipmentId(lineReq.getEquipmentId());
            line.setStartTime(lineReq.getStartTime());
            line.setEndTime(lineReq.getEndTime());
            lineMapper.insert(line);
        }

        ledgerService.append(project.getRelicId(), projectId, null,
                ACTION_TS_SUBMIT, project.getProjectNo(),
                "工时V" + sheet.getVersionNo() + "提交审核，修复师：" + sheet.getRestorer()
                        + "，共 " + lineNo + " 行", now);
        return sheet;
    }

    /**
     * 负责人审核：SUBMITTED -> APPROVED / RETURNED。
     * 退回必填意见；退回/通过后版本封存只读，任何补充只能另开新版本。
     */
    @Transactional
    public RepairTimesheet review(Long timesheetId, TimesheetReviewRequest req) {
        RepairTimesheet sheet = requireTimesheet(timesheetId);
        if (!TS_SUBMITTED.equals(sheet.getStatus())) {
            throw new IllegalArgumentException("工时V" + sheet.getVersionNo() + "已"
                    + (TS_RETURNED.equals(sheet.getStatus()) ? "退回" : "审核通过")
                    + "，保持只读，补充请提交新版本");
        }
        String decision = req.getDecision();
        if (!REVIEW_APPROVE.equals(decision) && !REVIEW_RETURN.equals(decision)) {
            throw new IllegalArgumentException("审核决定只能为 APPROVE/RETURN");
        }
        if (REVIEW_RETURN.equals(decision)
                && (req.getOpinion() == null || req.getOpinion().isEmpty())) {
            throw new IllegalArgumentException("退回必须填写审核意见");
        }

        RepairProject project = requireProject(sheet.getProjectId());
        LocalDateTime now = LocalDateTime.now();
        sheet.setStatus(REVIEW_APPROVE.equals(decision) ? TS_APPROVED : TS_RETURNED);
        sheet.setReviewedBy(req.getReviewedBy());
        sheet.setReviewedAt(now);
        sheet.setReviewOpinion(req.getOpinion());
        timesheetMapper.updateById(sheet);

        ledgerService.append(project.getRelicId(), project.getId(), null,
                REVIEW_APPROVE.equals(decision) ? ACTION_TS_APPROVE : ACTION_TS_RETURN,
                project.getProjectNo(),
                "工时V" + sheet.getVersionNo()
                        + (REVIEW_APPROVE.equals(decision) ? "审核通过" : "审核退回，版本封存只读")
                        + "，审核人：" + req.getReviewedBy()
                        + (req.getOpinion() == null ? "" : "，意见：" + req.getOpinion()), now);
        return sheet;
    }

    // ---- 查询 ----

    public RepairTimesheet requireTimesheet(Long timesheetId) {
        RepairTimesheet sheet = timesheetMapper.selectById(timesheetId);
        if (sheet == null) {
            throw new IllegalArgumentException("工时单不存在: id=" + timesheetId);
        }
        return sheet;
    }

    public List<RepairTimesheet> listVersions(Long projectId) {
        return timesheetMapper.selectList(new LambdaQueryWrapper<RepairTimesheet>()
                .eq(RepairTimesheet::getProjectId, projectId)
                .orderByAsc(RepairTimesheet::getVersionNo));
    }

    public List<RepairTimesheetLine> listLines(Long timesheetId) {
        return lineMapper.selectList(new LambdaQueryWrapper<RepairTimesheetLine>()
                .eq(RepairTimesheetLine::getTimesheetId, timesheetId)
                .orderByAsc(RepairTimesheetLine::getLineNo));
    }

    /** 工时单详情：版本 + 明细行 */
    public Map<String, Object> detail(Long timesheetId) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("timesheet", requireTimesheet(timesheetId));
        detail.put("lines", listLines(timesheetId));
        return detail;
    }

    private RepairProject requireProject(Long projectId) {
        RepairProject project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new IllegalArgumentException("修复项目不存在: id=" + projectId);
        }
        return project;
    }
}
