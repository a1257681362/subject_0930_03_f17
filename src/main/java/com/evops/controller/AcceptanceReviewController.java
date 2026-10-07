package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.AcceptConfirmRequest;
import com.evops.dto.AcceptReportSubmitRequest;
import com.evops.entity.AcceptReport;
import com.evops.service.AcceptanceReviewService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 修复成果验收：成果报告提交 -> 馆内专家/保管员分别逐项确认
 * -> 缺项整改（新一轮报告）-> 验收通过。
 * 详情、列表、CSV 导出共用同一行集口径，可交叉核对。
 */
@RestController
public class AcceptanceReviewController {

    private final AcceptanceReviewService reviewService;

    public AcceptanceReviewController(AcceptanceReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /** 修复师提交成果报告：按文物材质+工艺生成验收清单，轮次项目内递增 */
    @PostMapping("/api/repair-projects/{id}/accept-reports")
    public ApiResponse<Map<String, Object>> submit(@PathVariable Long id,
                                                   @Valid @RequestBody AcceptReportSubmitRequest request) {
        AcceptReport report = reviewService.submitReport(id, request);
        return ApiResponse.ok(reviewService.getReportDetail(report.getId()));
    }

    /** 项目报告轮次列表（含各轮清单与双方确认结果） */
    @GetMapping("/api/repair-projects/{id}/accept-reports")
    public ApiResponse<List<Map<String, Object>>> listByProject(@PathVariable Long id) {
        return ApiResponse.ok(reviewService.listByProject(id));
    }

    /** 逐项确认：角色 INTERNAL/CUSTODIAN，确认人须为指定确认人本人 */
    @PostMapping("/api/accept-reports/{reportId}/confirm")
    public ApiResponse<Map<String, Object>> confirm(@PathVariable Long reportId,
                                                    @Valid @RequestBody AcceptConfirmRequest request) {
        AcceptReport report = reviewService.confirm(reportId, request);
        return ApiResponse.ok(reviewService.getReportDetail(report.getId()));
    }

    /** 报告详情：报告 + 清单逐项确认结果 + 项目/文物口径 */
    @GetMapping("/api/accept-reports/{reportId}")
    public ApiResponse<Map<String, Object>> get(@PathVariable Long reportId) {
        return ApiResponse.ok(reviewService.getReportDetail(reportId));
    }

    /** 验收台账列表：可按文物 / 状态过滤，与导出同一行集 */
    @GetMapping("/api/accept-reports")
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestParam(required = false) Long relicId,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(reviewService.listRows(relicId, status));
    }

    /** CSV 导出：与列表查询同一行集同一顺序 */
    @GetMapping("/api/accept-reports/export")
    public ResponseEntity<String> export(@RequestParam(required = false) Long relicId,
                                         @RequestParam(required = false) String status) {
        String csv = reviewService.exportCsv(relicId, status);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.setContentDispositionFormData("attachment", "acceptance-reports.csv");
        return new ResponseEntity<>(csv, headers, org.springframework.http.HttpStatus.OK);
    }
}
