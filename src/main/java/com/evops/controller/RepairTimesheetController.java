package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.TimesheetReviewRequest;
import com.evops.dto.TimesheetSubmitRequest;
import com.evops.entity.RepairTimesheet;
import com.evops.service.RepairTimesheetService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 修复工时：修复师按工单填报（每次填报一个新版本），负责人审核；
 * 退回的版本封存只读，补充只能另开新版本。
 */
@RestController
public class RepairTimesheetController {

    private final RepairTimesheetService timesheetService;

    public RepairTimesheetController(RepairTimesheetService timesheetService) {
        this.timesheetService = timesheetService;
    }

    /** 填报工时：起止时间、工序、设备；设备同时段不能被两个工单占用 */
    @PostMapping("/api/repair-projects/{projectId}/timesheets")
    public ApiResponse<RepairTimesheet> submit(@PathVariable Long projectId,
                                               @Valid @RequestBody TimesheetSubmitRequest request) {
        return ApiResponse.ok(timesheetService.submit(projectId, request));
    }

    /** 工单的工时单版本列表 */
    @GetMapping("/api/repair-projects/{projectId}/timesheets")
    public ApiResponse<List<RepairTimesheet>> listVersions(@PathVariable Long projectId) {
        return ApiResponse.ok(timesheetService.listVersions(projectId));
    }

    /** 工时单详情：版本 + 明细行 */
    @GetMapping("/api/timesheets/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(timesheetService.detail(id));
    }

    /** 负责人审核：APPROVE 通过 / RETURN 退回（退回必填意见，封存只读） */
    @PostMapping("/api/timesheets/{id}/review")
    public ApiResponse<RepairTimesheet> review(@PathVariable Long id,
                                               @Valid @RequestBody TimesheetReviewRequest request) {
        return ApiResponse.ok(timesheetService.review(id, request));
    }
}
