package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.ValuationApproveRequest;
import com.evops.dto.ValuationRegisterRequest;
import com.evops.dto.ValuationReminderAckRequest;
import com.evops.dto.ValuationSubmitRequest;
import com.evops.entity.InsuranceValuationReminder;
import com.evops.entity.InsuranceValuationVersion;
import com.evops.service.InsuranceValuationReminderScheduler;
import com.evops.service.InsuranceValuationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 文物保险估值管理（museum-15）：
 *
 * <ul>
 *   <li>估值登记来源/评估机构/币种/有效期；金额修订只追加新版本，
 *       双人审批（初审+复核，两名不同人员）通过后生效并出具保险批单回填。</li>
 *   <li>借展签约时固化快照，借展期间一律读 {@code /loan-agreements/{id}/valuation-view}。</li>
 *   <li>过期自动提醒（不冻结归还）；财务视图仅金额与凭证；公众/普通馆员入口拒绝。</li>
 *   <li>收紧开关见 {@code PUT /api/policy/valuation-change}：
 *       缺权时本控制器下所有变更动作在服务入口统一拒绝，换任何 URL 都不放行。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class InsuranceValuationController {

    private final InsuranceValuationService valuationService;
    private final InsuranceValuationReminderScheduler reminderScheduler;

    public InsuranceValuationController(InsuranceValuationService valuationService,
                                        InsuranceValuationReminderScheduler reminderScheduler) {
        this.valuationService = valuationService;
        this.reminderScheduler = reminderScheduler;
    }

    /** 登记估值版本（默认草稿；submitNow=true 直接提交双人审批） */
    @PostMapping("/ins-valuations")
    public ApiResponse<InsuranceValuationVersion> register(
            @Valid @RequestBody ValuationRegisterRequest request) {
        return ApiResponse.ok(valuationService.register(request));
    }

    /** 草稿提交双人审批 */
    @PostMapping("/ins-valuations/{id}/submit")
    public ApiResponse<InsuranceValuationVersion> submit(
            @PathVariable Long id, @Valid @RequestBody ValuationSubmitRequest request) {
        return ApiResponse.ok(valuationService.submit(id, request));
    }

    /** 双人审批签署：FIRST 初审 / SECOND 复核（两人必须不同，且不得是提交人） */
    @PostMapping("/ins-valuations/{id}/approvals")
    public ApiResponse<InsuranceValuationVersion> approve(
            @PathVariable Long id, @Valid @RequestBody ValuationApproveRequest request) {
        return ApiResponse.ok(valuationService.approve(id, request));
    }

    /** 当前生效估值视图：按角色收口（财务仅金额/凭证；公众/普通馆员拒绝） */
    @GetMapping("/relics/{relicId}/insurance-valuation")
    public ApiResponse<Map<String, Object>> current(
            @PathVariable Long relicId,
            @RequestParam String operatorName,
            @RequestParam(defaultValue = "VALUATION_OFFICER") String role) {
        return ApiResponse.ok(valuationService.currentView(relicId, operatorName, role));
    }

    /** 估值版本履历：按角色收口（财务仅金额/凭证；公众/普通馆员拒绝） */
    @GetMapping("/relics/{relicId}/ins-valuation-versions")
    public ApiResponse<List<Map<String, Object>>> versions(
            @PathVariable Long relicId,
            @RequestParam String operatorName,
            @RequestParam(defaultValue = "VALUATION_OFFICER") String role) {
        return ApiResponse.ok(valuationService.versions(relicId, operatorName, role));
    }

    /**
     * 借展期间估值口径：始终返回签约时快照（不随后续修订/批单/过期变化）。
     * 财务只见快照金额与凭证；公众/普通馆员拒绝。
     */
    @GetMapping("/loan-agreements/{id}/valuation-view")
    public ApiResponse<Map<String, Object>> loanView(
            @PathVariable Long id,
            @RequestParam String operatorName,
            @RequestParam(defaultValue = "VALUATION_OFFICER") String role) {
        return ApiResponse.ok(valuationService.loanView(id, operatorName, role));
    }

    /** 协议历次签约估值快照：按角色收口 */
    @GetMapping("/loan-agreements/{id}/valuation-snapshots")
    public ApiResponse<List<Map<String, Object>>> snapshots(
            @PathVariable Long id,
            @RequestParam String operatorName,
            @RequestParam(defaultValue = "VALUATION_OFFICER") String role) {
        return ApiResponse.ok(valuationService.snapshots(id, operatorName, role));
    }

    /** 过期提醒列表（可按状态过滤） */
    @GetMapping("/ins-valuation-reminders")
    public ApiResponse<List<InsuranceValuationReminder>> reminders(
            @RequestParam(value = "status", required = false) String status) {
        return ApiResponse.ok(valuationService.listReminders(status));
    }

    /** 手动触发一轮过期扫描（测试/运维用；提醒不冻结归还） */
    @PostMapping("/ins-valuation-reminders/scan")
    public ApiResponse<Map<String, Object>> scan() {
        int created = reminderScheduler.scanOnce();
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("created", created);
        result.put("note", "过期仅提醒，不冻结借展归还；在借文物按签约快照履约");
        return ApiResponse.ok(result);
    }

    /** 人工确认过期提醒：只关提醒，不改变估值状态 */
    @PostMapping("/ins-valuation-reminders/{id}/ack")
    public ApiResponse<InsuranceValuationReminder> acknowledge(
            @PathVariable Long id, @Valid @RequestBody ValuationReminderAckRequest request) {
        return ApiResponse.ok(valuationService.acknowledgeReminder(id, request));
    }
}
