package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.ScheduleFilter;
import com.evops.dto.ScheduleRow;
import com.evops.service.RepairScheduleService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 修复工时与设备占用排程：页面列表、CSV 导出、后台按日统计三个出口
 * 共用同一查询口径（同一过滤参数、同一行集）。
 * 文物置为无效（REVOKED）后，三端默认都不再计入其行；
 * includeRevoked=true 时三端同样保留并以 REVOKED 标明。
 */
@RestController
@RequestMapping("/api/repair-schedule")
public class RepairScheduleController {

    private final RepairScheduleService scheduleService;

    public RepairScheduleController(RepairScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    /** 页面查询：有效工时行 + 设备占用排程 */
    @GetMapping
    public ApiResponse<List<ScheduleRow>> page(ScheduleFilter filter) {
        return ApiResponse.ok(scheduleService.query(filter));
    }

    /** 后台统计：同一行集按自然日拆分汇总（跨天作业拆到每天） */
    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary(ScheduleFilter filter) {
        return ApiResponse.ok(scheduleService.summary(filter));
    }

    /** CSV 导出：与页面查询同一行集同一顺序 */
    @GetMapping("/export")
    public ResponseEntity<String> export(ScheduleFilter filter) {
        String csv = scheduleService.exportCsv(filter);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.setContentDispositionFormData("attachment", "repair-schedule.csv");
        return new ResponseEntity<>(csv, headers, org.springframework.http.HttpStatus.OK);
    }
}
