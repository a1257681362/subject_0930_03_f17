package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.MaterialReviewConfirmRequest;
import com.evops.dto.MaterialReturnRequest;
import com.evops.dto.MaterialUsageRequest;
import com.evops.entity.MaterialReview;
import com.evops.entity.MaterialReturn;
import com.evops.entity.MaterialUsage;
import com.evops.service.MaterialAccountingService;
import com.evops.service.MaterialReviewScheduler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工单材料领用 / 退料 / 用量差异复核。
 */
@RestController
@RequestMapping("/api")
public class MaterialUsageController {

    private final MaterialAccountingService materialService;
    private final MaterialReviewScheduler reviewScheduler;

    public MaterialUsageController(MaterialAccountingService materialService,
                                   MaterialReviewScheduler reviewScheduler) {
        this.materialService = materialService;
        this.reviewScheduler = reviewScheduler;
    }

    /** 工单领用：逐行记录批号、计划/实际用量、部位、操作者；过期批号禁领 */
    @PostMapping("/material-usages")
    public ApiResponse<MaterialUsage> issue(@Valid @RequestBody MaterialUsageRequest request) {
        return ApiResponse.ok(materialService.issue(request));
    }

    /** 领用单详情：单头 + 明细行（含批号/操作者快照）+ 退料记录 */
    @GetMapping("/material-usages/{id}")
    public ApiResponse<Map<String, Object>> getUsage(@PathVariable Long id) {
        return ApiResponse.ok(materialService.getUsage(id));
    }

    /** 按修复工单查询领用记录 */
    @GetMapping("/material-usages")
    public ApiResponse<List<MaterialUsage>> listByProject(
            @RequestParam("projectId") Long projectId) {
        return ApiResponse.ok(materialService.listUsagesByProject(projectId));
    }

    /** 退料：必须关联原领用单与原明细行，库存加回原批号 */
    @PostMapping("/material-returns")
    public ApiResponse<MaterialReturn> returnMaterial(@Valid @RequestBody MaterialReturnRequest request) {
        return ApiResponse.ok(materialService.returnMaterial(request));
    }

    /** 复核清单，可按 OPEN/CONFIRMED 过滤 */
    @GetMapping("/material-reviews")
    public ApiResponse<List<MaterialReview>> listReviews(
            @RequestParam(value = "status", required = false) String status) {
        return ApiResponse.ok(materialService.listReviews(status));
    }

    /** 手动触发一轮差异复核扫描（定时任务之外的补扫入口，同样幂等） */
    @PostMapping("/material-reviews/scan")
    public ApiResponse<Map<String, Object>> scan() {
        int created = reviewScheduler.scanOnce();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("created", created);
        result.put("message", created > 0 ? "本轮补登记复核记录 " + created + " 条" : "无新增待复核记录");
        return ApiResponse.ok(result);
    }

    /** 复核确认：明细行解除待复核，原操作者保持不变 */
    @PostMapping("/material-reviews/{id}/confirm")
    public ApiResponse<MaterialReview> confirm(@PathVariable Long id,
                                               @Valid @RequestBody MaterialReviewConfirmRequest request) {
        return ApiResponse.ok(materialService.confirmReview(id, request.getConfirmedBy(), request.getNote()));
    }
}
