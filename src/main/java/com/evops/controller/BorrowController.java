package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.BorrowApplyRequest;
import com.evops.dto.BorrowApproveRequest;
import com.evops.dto.BorrowCancelRequest;
import com.evops.dto.BorrowExtensionRequest;
import com.evops.dto.BorrowLendRequest;
import com.evops.dto.BorrowPolicyUpdateRequest;
import com.evops.dto.BorrowReturnRequest;
import com.evops.entity.BorrowApplication;
import com.evops.entity.BorrowApprovalRecord;
import com.evops.entity.BorrowExtension;
import com.evops.entity.BorrowGradePolicy;
import com.evops.service.BorrowApplicationService;
import com.evops.service.BorrowPolicyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 文物借阅审批（museum-10）：
 * 按保护等级策略申请（校验展陈计划/修复状态）-> 按序审批（高风险追加馆长签名）
 * -> 通过生成借阅快照 -> 出库 -> 归还；归还延期重新走完整审批；
 * 保险资料按查看人馆区脱敏。
 */
@RestController
@RequestMapping("/api")
public class BorrowController {

    private final BorrowApplicationService applicationService;
    private final BorrowPolicyService policyService;

    public BorrowController(BorrowApplicationService applicationService,
                            BorrowPolicyService policyService) {
        this.applicationService = applicationService;
        this.policyService = policyService;
    }

    /** 借阅申请：场景/时长受保护等级策略约束，并校验当前展陈计划与修复状态 */
    @PostMapping("/relics/{relicId}/borrow-applications")
    public ApiResponse<BorrowApplication> apply(@PathVariable Long relicId,
                                                @Valid @RequestBody BorrowApplyRequest request) {
        return ApiResponse.ok(applicationService.apply(relicId, request));
    }

    /** 借阅申请列表（viewerCampus 与文物馆区不一致时保险金额脱敏） */
    @GetMapping("/borrow-applications")
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestParam(value = "relicId", required = false) Long relicId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "viewerCampus", required = false) String viewerCampus) {
        return ApiResponse.ok(applicationService.list(relicId, status, viewerCampus));
    }

    /** 申请详情：审批记录/借阅快照/延期重审链；保险金额按馆区脱敏 */
    @GetMapping("/borrow-applications/{id}")
    public ApiResponse<Map<String, Object>> detail(
            @PathVariable Long id,
            @RequestParam(value = "viewerCampus", required = false) String viewerCampus) {
        return ApiResponse.ok(applicationService.detail(id, viewerCampus));
    }

    /** 原申请链审批签署：按角色顺序；高风险链末步必须馆长签名 */
    @PostMapping("/borrow-applications/{id}/approvals")
    public ApiResponse<BorrowApprovalRecord> approve(@PathVariable Long id,
                                                     @Valid @RequestBody BorrowApproveRequest request) {
        return ApiResponse.ok(applicationService.approve(id, request));
    }

    /** 批准后出库（存在未闭环提前收回补偿时拒绝） */
    @PostMapping("/borrow-applications/{id}/lend")
    public ApiResponse<BorrowApplication> lend(@PathVariable Long id,
                                               @RequestBody(required = false) BorrowLendRequest request) {
        return ApiResponse.ok(applicationService.lend(id,
                request == null ? new BorrowLendRequest() : request));
    }

    /** 归还：在原单闭环；审批中的延期单自动驳回 */
    @PostMapping("/borrow-applications/{id}/return")
    public ApiResponse<BorrowApplication> repay(@PathVariable Long id,
                                                @Valid @RequestBody BorrowReturnRequest request) {
        return ApiResponse.ok(applicationService.repay(id, request));
    }

    /** 撤销申请 */
    @PostMapping("/borrow-applications/{id}/cancel")
    public ApiResponse<BorrowApplication> cancel(@PathVariable Long id,
                                                 @Valid @RequestBody BorrowCancelRequest request) {
        return ApiResponse.ok(applicationService.cancel(id, request));
    }

    /** 归还延期：重新走完整审批链 */
    @PostMapping("/borrow-applications/{id}/extensions")
    public ApiResponse<BorrowExtension> applyExtension(@PathVariable Long id,
                                                       @Valid @RequestBody BorrowExtensionRequest request) {
        return ApiResponse.ok(applicationService.applyExtension(id, request));
    }

    /** 延期重审链签署 */
    @PostMapping("/borrow-extensions/{extensionId}/approvals")
    public ApiResponse<BorrowApprovalRecord> approveExtension(@PathVariable Long extensionId,
                                                              @Valid @RequestBody BorrowApproveRequest request) {
        return ApiResponse.ok(applicationService.approveExtension(extensionId, request));
    }

    /** 查看各保护等级借阅策略当前值 */
    @GetMapping("/borrow-policies")
    public ApiResponse<List<BorrowGradePolicy>> listPolicies() {
        return ApiResponse.ok(policyService.listPolicies());
    }

    /** 调整某保护等级借阅策略（只影响之后提交的申请） */
    @PutMapping("/borrow-policies/{grade}")
    public ApiResponse<BorrowGradePolicy> updatePolicy(@PathVariable String grade,
                                                       @Valid @RequestBody BorrowPolicyUpdateRequest request) {
        return ApiResponse.ok(policyService.updatePolicy(grade, request));
    }
}
