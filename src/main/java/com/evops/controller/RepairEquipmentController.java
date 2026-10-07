package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.EquipmentCreateRequest;
import com.evops.dto.FaultReportRequest;
import com.evops.dto.SuggestionHandleRequest;
import com.evops.entity.RepairEquipment;
import com.evops.entity.RepairEquipmentFault;
import com.evops.entity.RepairRescheduleSuggestion;
import com.evops.service.RepairEquipmentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 修复设备与故障改期：设备建档、故障登记（自动生成改期建议但不改原排程）、
 * 故障恢复、改期建议处理。
 */
@RestController
public class RepairEquipmentController {

    private final RepairEquipmentService equipmentService;

    public RepairEquipmentController(RepairEquipmentService equipmentService) {
        this.equipmentService = equipmentService;
    }

    /** 设备建档：编号唯一 */
    @PostMapping("/api/repair-equipment")
    public ApiResponse<RepairEquipment> create(@Valid @RequestBody EquipmentCreateRequest request) {
        return ApiResponse.ok(equipmentService.create(request));
    }

    /** 设备列表，可按状态过滤 */
    @GetMapping("/api/repair-equipment")
    public ApiResponse<List<RepairEquipment>> list(
            @RequestParam(value = "status", required = false) String status) {
        return ApiResponse.ok(equipmentService.list(status));
    }

    /** 设备详情：档案 + 故障单 + 改期建议 */
    @GetMapping("/api/repair-equipment/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("equipment", equipmentService.requireEquipment(id));
        detail.put("faults", equipmentService.listFaults(id));
        detail.put("suggestions", equipmentService.listSuggestions(id, null, null));
        return ApiResponse.ok(detail);
    }

    /** 故障登记：设备置故障中，自动生成改期建议（原排程不变） */
    @PostMapping("/api/repair-equipment/{id}/faults")
    public ApiResponse<Map<String, Object>> reportFault(@PathVariable Long id,
                                                        @Valid @RequestBody FaultReportRequest request) {
        return ApiResponse.ok(equipmentService.reportFault(id, request));
    }

    /** 故障恢复：关闭故障单，设备回到在用 */
    @PostMapping("/api/repair-equipment/{id}/faults/{faultId}/close")
    public ApiResponse<RepairEquipmentFault> closeFault(@PathVariable Long id,
                                                        @PathVariable Long faultId,
                                                        @Valid @RequestBody SuggestionHandleRequest request) {
        return ApiResponse.ok(equipmentService.closeFault(id, faultId, request.getHandledBy()));
    }

    /** 改期建议列表：可按设备/工单/状态过滤 */
    @GetMapping("/api/repair-suggestions")
    public ApiResponse<List<RepairRescheduleSuggestion>> listSuggestions(
            @RequestParam(value = "equipmentId", required = false) Long equipmentId,
            @RequestParam(value = "projectId", required = false) Long projectId,
            @RequestParam(value = "status", required = false) String status) {
        return ApiResponse.ok(equipmentService.listSuggestions(equipmentId, projectId, status));
    }

    /** 采纳建议：只记录处理意见，不改写原排程 */
    @PostMapping("/api/repair-suggestions/{id}/adopt")
    public ApiResponse<RepairRescheduleSuggestion> adopt(
            @PathVariable Long id, @Valid @RequestBody SuggestionHandleRequest request) {
        return ApiResponse.ok(equipmentService.handleSuggestion(id, "adopt", request.getHandledBy()));
    }

    /** 忽略建议：只记录处理意见，不改写原排程 */
    @PostMapping("/api/repair-suggestions/{id}/dismiss")
    public ApiResponse<RepairRescheduleSuggestion> dismiss(
            @PathVariable Long id, @Valid @RequestBody SuggestionHandleRequest request) {
        return ApiResponse.ok(equipmentService.handleSuggestion(id, "dismiss", request.getHandledBy()));
    }
}
