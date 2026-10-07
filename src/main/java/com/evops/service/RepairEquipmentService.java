package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.dto.EquipmentCreateRequest;
import com.evops.dto.FaultReportRequest;
import com.evops.dto.ScheduleFilter;
import com.evops.dto.ScheduleRow;
import com.evops.entity.RepairEquipment;
import com.evops.entity.RepairEquipmentFault;
import com.evops.entity.RepairRescheduleSuggestion;
import com.evops.mapper.RepairEquipmentFaultMapper;
import com.evops.mapper.RepairEquipmentMapper;
import com.evops.mapper.RepairRescheduleSuggestionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.evops.constant.MuseumConstants.*;

/**
 * 修复设备与故障改期：
 * <ul>
 *   <li>设备建档：编号唯一，状态 ACTIVE/FAULT，故障只置状态不删历史。</li>
 *   <li>故障登记：设备置 FAULT，自动为受影响的在排程工时行生成改期建议
 *       （建议起点=预计恢复时刻，时长与原行一致）；<b>原排程（工时明细行）
 *       保持原样，绝不被悄悄改写</b>——实际改期只能走工时单新版本。</li>
 *   <li>建议处理：采纳/忽略只改建议自身状态，不回写工时行。</li>
 * </ul>
 */
@Service
public class RepairEquipmentService {

    private final RepairEquipmentMapper equipmentMapper;
    private final RepairEquipmentFaultMapper faultMapper;
    private final RepairRescheduleSuggestionMapper suggestionMapper;
    private final RepairScheduleService scheduleService;
    private final LedgerService ledgerService;

    public RepairEquipmentService(RepairEquipmentMapper equipmentMapper,
                                  RepairEquipmentFaultMapper faultMapper,
                                  RepairRescheduleSuggestionMapper suggestionMapper,
                                  RepairScheduleService scheduleService,
                                  LedgerService ledgerService) {
        this.equipmentMapper = equipmentMapper;
        this.faultMapper = faultMapper;
        this.suggestionMapper = suggestionMapper;
        this.scheduleService = scheduleService;
        this.ledgerService = ledgerService;
    }

    /** 设备建档：编号全库唯一（含历史设备） */
    @Transactional
    public RepairEquipment create(EquipmentCreateRequest req) {
        if (equipmentMapper.selectCount(new LambdaQueryWrapper<RepairEquipment>()
                .eq(RepairEquipment::getEquipmentNo, req.getEquipmentNo())) > 0) {
            throw new IllegalArgumentException("设备编号已存在: " + req.getEquipmentNo());
        }
        RepairEquipment equipment = new RepairEquipment();
        equipment.setEquipmentNo(req.getEquipmentNo());
        equipment.setName(req.getName());
        equipment.setCategory(req.getCategory());
        equipment.setLocation(req.getLocation());
        equipment.setRemark(req.getRemark());
        equipment.setStatus(EQUIP_ACTIVE);
        equipmentMapper.insert(equipment);
        return equipment;
    }

    /**
     * 故障登记：设备置 FAULT，为受影响工时行生成改期建议。
     * 受影响口径：修复中工单的有效（SUBMITTED/APPROVED）工时行，
     * 时段与 [故障发生, 预计恢复) 重叠。建议只追加，原排程不变。
     */
    @Transactional
    public Map<String, Object> reportFault(Long equipmentId, FaultReportRequest req) {
        RepairEquipment equipment = requireEquipment(equipmentId);
        LocalDateTime faultAt = req.getFaultAt() == null ? LocalDateTime.now() : req.getFaultAt();
        if (!req.getExpectedRecoveredAt().isAfter(faultAt)) {
            throw new IllegalArgumentException("预计恢复时刻必须晚于故障发生时刻");
        }

        // 设备行锁：与工时提交串行化，避免建议生成与占用判定互相踩踏
        equipmentMapper.lockRow(equipmentId);
        equipment = equipmentMapper.selectById(equipmentId);
        if (!EQUIP_ACTIVE.equals(equipment.getStatus())) {
            throw new IllegalArgumentException("设备 " + equipment.getEquipmentNo()
                    + " 已处于故障中，不能重复登记故障");
        }

        LocalDateTime now = LocalDateTime.now();
        equipment.setStatus(EQUIP_FAULT);
        equipmentMapper.updateById(equipment);

        RepairEquipmentFault fault = new RepairEquipmentFault();
        fault.setFaultNo(String.format("FT-%06d", faultMapper.nextFaultSeq()));
        fault.setEquipmentId(equipmentId);
        fault.setDescription(req.getDescription());
        fault.setFaultAt(faultAt);
        fault.setExpectedRecoveredAt(req.getExpectedRecoveredAt());
        fault.setStatus(FAULT_OPEN);
        faultMapper.insert(fault);

        // 受影响行：修复中工单 + 有效版本 + 与故障窗口重叠
        ScheduleFilter filter = new ScheduleFilter();
        filter.setEquipmentId(equipmentId);
        filter.setFrom(faultAt);
        filter.setTo(req.getExpectedRecoveredAt());
        filter.setOnlyRepairingProjects(true);
        filter.setIncludeRevoked(true); // 建议生成不受文物撤销过滤影响，按事实行集判定
        List<ScheduleRow> affected = scheduleService.query(filter);

        Map<Long, Integer> projectSuggestCount = new LinkedHashMap<>();
        Map<Long, String> projectNoById = new LinkedHashMap<>();
        Map<Long, Long> relicIdByProjectId = new LinkedHashMap<>();
        int suggestCount = 0;
        for (ScheduleRow row : affected) {
            RepairRescheduleSuggestion suggestion = new RepairRescheduleSuggestion();
            suggestion.setFaultId(fault.getId());
            suggestion.setTimesheetLineId(row.getLineId());
            suggestion.setProjectId(row.getProjectId());
            suggestion.setEquipmentId(equipmentId);
            suggestion.setOriginStart(row.getStartTime());
            suggestion.setOriginEnd(row.getEndTime());
            // 建议：平移到预计恢复时刻之后，时长与原行一致；原行保持不动
            long durationMinutes = Duration.between(row.getStartTime(), row.getEndTime()).toMinutes();
            suggestion.setSuggestedStart(req.getExpectedRecoveredAt());
            suggestion.setSuggestedEnd(req.getExpectedRecoveredAt().plusMinutes(durationMinutes));
            suggestion.setStatus(SUGGEST_PENDING);
            suggestionMapper.insert(suggestion);
            suggestCount++;
            projectSuggestCount.merge(row.getProjectId(), 1, Integer::sum);
            projectNoById.put(row.getProjectId(), row.getProjectNo());
            relicIdByProjectId.put(row.getProjectId(), row.getRelicId());
        }

        // 台账：按受影响工单逐条留痕（设备故障不属单一文物，无法写无 relic 的台账行）
        for (Map.Entry<Long, Integer> entry : projectSuggestCount.entrySet()) {
            ledgerService.append(relicIdByProjectId.get(entry.getKey()), entry.getKey(), null,
                    ACTION_RESCHEDULE_SUGGEST, projectNoById.get(entry.getKey()),
                    "设备 " + equipment.getEquipmentNo() + " 故障（" + fault.getFaultNo()
                            + "），生成 " + entry.getValue() + " 条改期建议；原排程不变，"
                            + "实际改期须提交工时单新版本", now);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fault", fault);
        result.put("suggestCount", suggestCount);
        result.put("suggestions", listSuggestionsByFault(fault.getId()));
        return result;
    }

    /**
     * 故障恢复：故障单关闭；设备无其他未闭环故障时回到在用。
     */
    @Transactional
    public RepairEquipmentFault closeFault(Long equipmentId, Long faultId, String closedBy) {
        RepairEquipment equipment = requireEquipment(equipmentId);
        RepairEquipmentFault fault = requireFault(faultId);
        if (!equipmentId.equals(fault.getEquipmentId())) {
            throw new IllegalArgumentException("故障单 " + fault.getFaultNo() + " 不属于设备 "
                    + equipment.getEquipmentNo());
        }
        if (!FAULT_OPEN.equals(fault.getStatus())) {
            throw new IllegalArgumentException("故障单 " + fault.getFaultNo() + " 已关闭，不能重复关闭");
        }

        equipmentMapper.lockRow(equipmentId);
        LocalDateTime now = LocalDateTime.now();
        fault.setStatus(FAULT_CLOSED);
        fault.setClosedBy(closedBy);
        fault.setClosedAt(now);
        faultMapper.updateById(fault);

        Long openFaults = faultMapper.selectCount(new LambdaQueryWrapper<RepairEquipmentFault>()
                .eq(RepairEquipmentFault::getEquipmentId, equipmentId)
                .eq(RepairEquipmentFault::getStatus, FAULT_OPEN));
        if (openFaults == null || openFaults == 0) {
            RepairEquipment latest = equipmentMapper.selectById(equipmentId);
            latest.setStatus(EQUIP_ACTIVE);
            equipmentMapper.updateById(latest);
        }
        return fault;
    }

    /**
     * 处理改期建议：采纳/忽略。只改建议自身状态留痕，
     * 不回写工时明细行——原排程的变更只能走工时单新版本。
     */
    @Transactional
    public RepairRescheduleSuggestion handleSuggestion(Long suggestionId, String action, String handledBy) {
        RepairRescheduleSuggestion suggestion = requireSuggestion(suggestionId);
        if (!SUGGEST_PENDING.equals(suggestion.getStatus())) {
            throw new IllegalArgumentException("建议已处理（" + suggestion.getStatus()
                    + "），不能重复处理");
        }
        boolean adopt = "adopt".equals(action);
        suggestion.setStatus(adopt ? SUGGEST_ADOPTED : SUGGEST_DISMISSED);
        suggestion.setHandledBy(handledBy);
        suggestion.setHandledAt(LocalDateTime.now());
        suggestionMapper.updateById(suggestion);
        return suggestion;
    }

    // ---- 查询 ----

    public RepairEquipment requireEquipment(Long id) {
        RepairEquipment equipment = equipmentMapper.selectById(id);
        if (equipment == null) {
            throw new IllegalArgumentException("设备不存在: id=" + id);
        }
        return equipment;
    }

    public RepairEquipmentFault requireFault(Long faultId) {
        RepairEquipmentFault fault = faultMapper.selectById(faultId);
        if (fault == null) {
            throw new IllegalArgumentException("故障单不存在: id=" + faultId);
        }
        return fault;
    }

    public RepairRescheduleSuggestion requireSuggestion(Long suggestionId) {
        RepairRescheduleSuggestion suggestion = suggestionMapper.selectById(suggestionId);
        if (suggestion == null) {
            throw new IllegalArgumentException("改期建议不存在: id=" + suggestionId);
        }
        return suggestion;
    }

    public List<RepairEquipment> list(String status) {
        return equipmentMapper.selectList(new LambdaQueryWrapper<RepairEquipment>()
                .eq(status != null && !status.isEmpty(), RepairEquipment::getStatus, status)
                .orderByAsc(RepairEquipment::getId));
    }

    public List<RepairEquipmentFault> listFaults(Long equipmentId) {
        return faultMapper.selectList(new LambdaQueryWrapper<RepairEquipmentFault>()
                .eq(equipmentId != null, RepairEquipmentFault::getEquipmentId, equipmentId)
                .orderByDesc(RepairEquipmentFault::getId));
    }

    public List<RepairRescheduleSuggestion> listSuggestionsByFault(Long faultId) {
        return suggestionMapper.selectList(new LambdaQueryWrapper<RepairRescheduleSuggestion>()
                .eq(RepairRescheduleSuggestion::getFaultId, faultId)
                .orderByAsc(RepairRescheduleSuggestion::getId));
    }

    public List<RepairRescheduleSuggestion> listSuggestions(Long equipmentId, Long projectId,
                                                            String status) {
        return suggestionMapper.selectList(new LambdaQueryWrapper<RepairRescheduleSuggestion>()
                .eq(equipmentId != null, RepairRescheduleSuggestion::getEquipmentId, equipmentId)
                .eq(projectId != null, RepairRescheduleSuggestion::getProjectId, projectId)
                .eq(status != null && !status.isEmpty(), RepairRescheduleSuggestion::getStatus, status)
                .orderByDesc(RepairRescheduleSuggestion::getId));
    }
}
