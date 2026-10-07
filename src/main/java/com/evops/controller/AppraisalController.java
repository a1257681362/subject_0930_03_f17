package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.AppraisalCaseCreateRequest;
import com.evops.dto.ArbitrationConcludeRequest;
import com.evops.dto.OpinionCorrectionRequest;
import com.evops.dto.OpinionOperatorRequest;
import com.evops.dto.OpinionSubmitRequest;
import com.evops.entity.AppraisalArbitration;
import com.evops.entity.AppraisalCase;
import com.evops.entity.AppraisalCorrection;
import com.evops.entity.AppraisalOpinion;
import com.evops.entity.AppraisalReportAudit;
import com.evops.service.AppraisalService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 文物鉴定意见：
 *   开立案件 -> 初鉴（草稿/发布）-> 复鉴（草稿/发布）
 *   -> 一致定稿 / 冲突仲裁定稿；
 *   未发布可撤回、已发布只能追加更正；普通馆员仅见结论摘要，
 *   详细报告下载（含被拒绝）逐条记录审计流水。
 */
@RestController
@RequestMapping("/api/appraisal-cases")
public class AppraisalController {

    private final AppraisalService appraisalService;

    public AppraisalController(AppraisalService appraisalService) {
        this.appraisalService = appraisalService;
    }

    /** 开立鉴定案件 */
    @PostMapping
    public ApiResponse<AppraisalCase> openCase(
            @Valid @RequestBody AppraisalCaseCreateRequest request) {
        return ApiResponse.ok(appraisalService.openCase(request));
    }

    /** 提交初鉴/复鉴意见（publish=true 直接发布，否则存为可撤回的草稿） */
    @PostMapping("/{id}/opinions")
    public ApiResponse<AppraisalOpinion> submitOpinion(
            @PathVariable("id") Long caseId,
            @Valid @RequestBody OpinionSubmitRequest request) {
        return ApiResponse.ok(appraisalService.submitOpinion(caseId, request));
    }

    /** 发布未发布草稿（专家本人） */
    @PostMapping("/opinions/{opinionId}/publish")
    public ApiResponse<AppraisalOpinion> publishOpinion(
            @PathVariable Long opinionId,
            @Valid @RequestBody OpinionOperatorRequest request) {
        return ApiResponse.ok(appraisalService.publishOpinion(opinionId, request));
    }

    /** 撤回未发布意见（专家本人，撤回留痕不删档） */
    @PostMapping("/opinions/{opinionId}/withdraw")
    public ApiResponse<AppraisalOpinion> withdrawOpinion(
            @PathVariable Long opinionId,
            @Valid @RequestBody OpinionOperatorRequest request) {
        return ApiResponse.ok(appraisalService.withdrawOpinion(opinionId, request));
    }

    /** 已发布意见追加更正（只追加，原文不改写） */
    @PostMapping("/opinions/{opinionId}/corrections")
    public ApiResponse<AppraisalCorrection> addCorrection(
            @PathVariable Long opinionId,
            @Valid @RequestBody OpinionCorrectionRequest request) {
        return ApiResponse.ok(appraisalService.addCorrection(opinionId, request));
    }

    /** 仲裁定稿：采初鉴/采复鉴/综合调整 */
    @PostMapping("/arbitrations/{arbitrationId}/conclude")
    public ApiResponse<AppraisalArbitration> concludeArbitration(
            @PathVariable Long arbitrationId,
            @Valid @RequestBody ArbitrationConcludeRequest request) {
        return ApiResponse.ok(appraisalService.concludeArbitration(arbitrationId, request));
    }

    /**
     * 详细报告下载：仅鉴定专家/仲裁人放行；普通馆员拒绝。
     * 放行与拒绝均写一条审计流水。角色/操作人沿用以 ?role= / 请求头传参的本地登记口径。
     */
    @PostMapping("/{id}/report-download")
    public ApiResponse<Map<String, Object>> downloadReport(
            @PathVariable("id") Long caseId,
            @RequestParam String operatorName,
            @RequestParam(defaultValue = "LIBRARIAN") String role,
            @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor,
            HttpServletRequest httpRequest) {
        String clientIp = forwardedFor != null && !forwardedFor.isEmpty()
                ? forwardedFor.split(",")[0].trim() : httpRequest.getRemoteAddr();
        return ApiResponse.ok(
                appraisalService.downloadReport(caseId, operatorName, role, clientIp));
    }

    /** 报告下载审计流水（管理/审计视角） */
    @GetMapping("/{id}/report-audits")
    public ApiResponse<List<AppraisalReportAudit>> listReportAudits(
            @PathVariable("id") Long caseId) {
        return ApiResponse.ok(appraisalService.listReportAudits(caseId));
    }

    /** 案件全量详情：专家/仲裁视角（两轮意见+更正+附件+仲裁单） */
    @GetMapping("/{id}/detail")
    public ApiResponse<Map<String, Object>> detail(@PathVariable("id") Long caseId) {
        return ApiResponse.ok(appraisalService.detail(caseId));
    }

    /** 普通馆员视角：仅结论摘要，无专家/依据/置信说明/附件 */
    @GetMapping("/{id}/summary")
    public ApiResponse<Map<String, Object>> summary(@PathVariable("id") Long caseId) {
        return ApiResponse.ok(appraisalService.summary(caseId));
    }
}
