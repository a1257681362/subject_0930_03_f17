package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.AnnualAlertAckRequest;
import com.evops.dto.AnnualAnomalyAppendRequest;
import com.evops.dto.AnnualCaliberPublishRequest;
import com.evops.dto.AnnualReportGenerateRequest;
import com.evops.dto.AnnualReportResubmitRequest;
import com.evops.dto.AnnualReportRevokeRequest;
import com.evops.entity.AnnualCaliberVersion;
import com.evops.entity.AnnualReport;
import com.evops.entity.AnnualReportAlert;
import com.evops.entity.AnnualReportAnomaly;
import com.evops.entity.AnnualReportJobLog;
import com.evops.service.AnnualReportGenerationScheduler;
import com.evops.service.AnnualReportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 文物修复年度报表（museum-16）：
 * 口径版本管理、归档生成（手动/Quartz）、撤回重提、异常凭证追加、
 * 失败告警与任务日志、按馆区/保密级别裁剪的 CSV 导出。
 */
@RestController
@RequestMapping("/api")
public class AnnualReportController {

    private final AnnualReportService annualReportService;
    private final AnnualReportGenerationScheduler scheduler;

    public AnnualReportController(AnnualReportService annualReportService,
                                  AnnualReportGenerationScheduler scheduler) {
        this.annualReportService = annualReportService;
        this.scheduler = scheduler;
    }

    // ---- 指标口径版本 ----

    /** 发布口径新版本（旧版本置 SUPERSEDED 只追加） */
    @PostMapping("/annual-calibers")
    public ApiResponse<AnnualCaliberVersion> publishCaliber(
            @Valid @RequestBody AnnualCaliberPublishRequest request) {
        return ApiResponse.ok(annualReportService.publishCaliber(request));
    }

    @GetMapping("/annual-calibers")
    public ApiResponse<List<AnnualCaliberVersion>> listCalibers() {
        return ApiResponse.ok(annualReportService.listCalibers());
    }

    @GetMapping("/annual-calibers/active")
    public ApiResponse<AnnualCaliberVersion> activeCaliber() {
        return ApiResponse.ok(annualReportService.activeCaliber());
    }

    // ---- 年度报表归档 ----

    /** 手动生成：同样落任务日志，失败出 FAILED 日志与 OPEN 告警 */
    @PostMapping("/annual-reports/generate")
    public ApiResponse<AnnualReport> generate(@Valid @RequestBody AnnualReportGenerateRequest request) {
        return ApiResponse.ok(scheduler.fireManual(request.getYear(), request.getCaliberVersionId(),
                request.getCampusScope(), request.getGeneratedBy(), null));
    }

    /**
     * 模拟 Quartz 定时触发（测试/运维用）：triggeredBy=SCHEDULED，
     * 无口径版本等失败时保留 FAILED 任务日志并告警。
     */
    @PostMapping("/annual-reports/scheduled-fire")
    public ApiResponse<AnnualReport> scheduledFire(@RequestParam("year") Integer year,
                                                   @RequestParam(value = "campusScope", required = false)
                                                   String campusScope,
                                                   @RequestParam(value = "generatedBy", required = false)
                                                   String generatedBy) {
        String by = generatedBy == null || generatedBy.trim().isEmpty() ? "系统定时" : generatedBy;
        return ApiResponse.ok(scheduler.fireScheduled(year, campusScope, by, "MANUAL-FIRE"));
    }

    @GetMapping("/annual-reports")
    public ApiResponse<List<AnnualReport>> list(@RequestParam(value = "year", required = false) Integer year) {
        return ApiResponse.ok(annualReportService.listReports(year));
    }

    @GetMapping("/annual-reports/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(annualReportService.detail(id));
    }

    /** 撤回归档报表（快照保留，只置 REVOKED） */
    @PostMapping("/annual-reports/{id}/revoke")
    public ApiResponse<AnnualReport> revoke(@PathVariable Long id,
                                            @Valid @RequestBody AnnualReportRevokeRequest request) {
        return ApiResponse.ok(annualReportService.revoke(id, request.getReason(), request.getRevokedBy()));
    }

    /** 撤回后重提：新批次、新编号、新时间，撤回链可辨 */
    @PostMapping("/annual-reports/{id}/resubmit")
    public ApiResponse<AnnualReport> resubmit(@PathVariable Long id,
                                              @Valid @RequestBody AnnualReportResubmitRequest request) {
        return ApiResponse.ok(annualReportService.resubmit(id, request.getCaliberVersionId(),
                request.getGeneratedBy()));
    }

    /** 追加异常凭证（只追加，不覆盖已有凭证） */
    @PostMapping("/annual-reports/{id}/anomalies")
    public ApiResponse<AnnualReportAnomaly> appendAnomaly(
            @PathVariable Long id, @Valid @RequestBody AnnualAnomalyAppendRequest request) {
        return ApiResponse.ok(annualReportService.appendAnomaly(id, request.getAnomalyType(),
                request.getSourceId(), request.getDetail(), request.getAppendedBy()));
    }

    /** CSV 导出：按馆区整行裁剪、按导出角色对保密一级字段裁剪 */
    @GetMapping("/annual-reports/{id}/export")
    public ResponseEntity<String> export(@PathVariable Long id,
                                         @RequestParam(value = "viewerCampus", required = false)
                                         String viewerCampus,
                                         @RequestParam(value = "role", required = false) String role) {
        AnnualReport report = annualReportService.requireReport(id);
        String csv = annualReportService.exportCsv(id, viewerCampus, role);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.setContentDispositionFormData("attachment",
                report.getReportNo() + ".csv");
        return new ResponseEntity<>(csv, headers, HttpStatus.OK);
    }

    // ---- 失败告警与任务日志 ----

    @GetMapping("/annual-report-alerts")
    public ApiResponse<List<AnnualReportAlert>> listAlerts(
            @RequestParam(value = "status", required = false) String status) {
        return ApiResponse.ok(annualReportService.listAlerts(status));
    }

    @PostMapping("/annual-report-alerts/{id}/ack")
    public ApiResponse<AnnualReportAlert> acknowledge(@PathVariable Long id,
                                                      @Valid @RequestBody AnnualAlertAckRequest request) {
        return ApiResponse.ok(annualReportService.acknowledgeAlert(id,
                request.getAcknowledgedBy(), request.getNote()));
    }

    @GetMapping("/annual-report-job-logs")
    public ApiResponse<List<AnnualReportJobLog>> listJobLogs(
            @RequestParam(value = "year", required = false) Integer year) {
        return ApiResponse.ok(annualReportService.listJobLogs(year));
    }
}
