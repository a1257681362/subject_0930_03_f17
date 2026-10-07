package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.PlanSignRequest;
import com.evops.dto.PlanVersionCreateRequest;
import com.evops.entity.RepairPlanSign;
import com.evops.entity.RepairPlanVersion;
import com.evops.service.RepairPlanService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 修复方案会签：方案版本提交 -> 按风险配置的顺序会签 -> 退回只读/全部通过。
 */
@RestController
public class RepairPlanController {

    private final RepairPlanService planService;

    public RepairPlanController(RepairPlanService planService) {
        this.planService = planService;
    }

    /** 提交新方案版本：指定三类应签人；被退回的方案只能修订为新版本 */
    @PostMapping("/api/repair-projects/{id}/plan-versions")
    public ApiResponse<Map<String, Object>> createVersion(@PathVariable Long id,
                                                          @Valid @RequestBody PlanVersionCreateRequest request) {
        RepairPlanVersion version = planService.createVersion(id, request);
        return ApiResponse.ok(detail(version));
    }

    /** 项目方案版本列表（含各版本会签记录） */
    @GetMapping("/api/repair-projects/{id}/plan-versions")
    public ApiResponse<List<Map<String, Object>>> listVersions(@PathVariable Long id) {
        List<Map<String, Object>> versions = planService.listVersions(id).stream()
                .map(this::detail)
                .collect(Collectors.toList());
        return ApiResponse.ok(versions);
    }

    /** 会签签署：顺序、应签人由系统校验，缺席不允许代签 */
    @PostMapping("/api/plan-versions/{versionId}/sign")
    public ApiResponse<RepairPlanSign> sign(@PathVariable Long versionId,
                                            @Valid @RequestBody PlanSignRequest request) {
        return ApiResponse.ok(planService.sign(versionId, request));
    }

    /** 方案版本详情：版本 + 会签顺序 + 会签记录 */
    @GetMapping("/api/plan-versions/{versionId}")
    public ApiResponse<Map<String, Object>> getVersion(@PathVariable Long versionId) {
        return ApiResponse.ok(detail(planService.requireVersion(versionId)));
    }

    private Map<String, Object> detail(RepairPlanVersion version) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("version", version);
        detail.put("signOrder", planService.signOrder(version));
        detail.put("signs", planService.listSigns(version.getId()));
        return detail;
    }
}
