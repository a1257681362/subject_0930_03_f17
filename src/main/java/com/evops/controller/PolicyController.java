package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.PolicyUpdateRequest;
import com.evops.entity.PermissionPolicy;
import com.evops.service.LedgerService;
import com.evops.service.PermissionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.time.LocalDateTime;

import static com.evops.constant.MuseumConstants.ACTION_POLICY_RELAX;
import static com.evops.constant.MuseumConstants.ACTION_POLICY_TIGHTEN;
import static com.evops.constant.MuseumConstants.ACTION_VAL_POLICY_RELAX;
import static com.evops.constant.MuseumConstants.ACTION_VAL_POLICY_TIGHTEN;

/**
 * 文物修复权限策略：收紧后影像上传停止推进，放开后恢复。
 * 已确认的影像版本、物理文件与台账事实不因收紧而改动或删除。
 *
 * <p>{@code /api/policy/valuation-change} 另管「文物保险估值变更留痕」入口：
 * 收紧后估值登记/金额修订/双人审批/批单/签约快照等一切变更动作在服务入口拒绝，
 * 换调用地址也不放行；已生效估值、批单与借展快照只读保留。</p>
 */
@RestController
@RequestMapping("/api/policy")
public class PolicyController {

    /** 台账中全局策略事件使用的占位文物 id（无具体文物归属） */
    private static final long GLOBAL_SCOPE = 0L;

    private final PermissionService permissionService;
    private final LedgerService ledgerService;

    public PolicyController(PermissionService permissionService,
                            LedgerService ledgerService) {
        this.permissionService = permissionService;
        this.ledgerService = ledgerService;
    }

    /** 查看当前策略 */
    @GetMapping("/repair-photo-upload")
    public ApiResponse<PermissionPolicy> get() {
        return ApiResponse.ok(permissionService.getPolicy());
    }

    /** 收紧（open=false）/ 放开（open=true）影像上传，并写台账留痕 */
    @PutMapping("/repair-photo-upload")
    public ApiResponse<PermissionPolicy> update(@Valid @RequestBody PolicyUpdateRequest request) {
        boolean wasOpen = permissionService.isUploadOpen();
        PermissionPolicy policy = permissionService.updateOpen(
                request.getOpen(), request.getOperator(), request.getReason());

        if (wasOpen != request.getOpen()) {
            String operator = request.getOperator() == null ? "SYSTEM" : request.getOperator();
            String summary = (request.getOpen() ? "文物修复影像上传权限放开"
                    : "博物馆文物修复权限收紧，影像上传停止推进，已确认版本归档继续保留")
                    + "，操作人：" + operator
                    + (request.getReason() != null && !request.getReason().isEmpty()
                            ? "，原因：" + request.getReason() : "");
            ledgerService.append(GLOBAL_SCOPE, null, null,
                    request.getOpen() ? ACTION_POLICY_RELAX : ACTION_POLICY_TIGHTEN,
                    "POLICY#1", summary, LocalDateTime.now());
        }
        return ApiResponse.ok(policy);
    }

    /** 查看文物保险估值变更留痕入口开关 */
    @GetMapping("/valuation-change")
    public ApiResponse<PermissionPolicy> getValuationChange() {
        return ApiResponse.ok(permissionService.getPolicy());
    }

    /**
     * 收紧（open=false）/ 放开（open=true）文物保险估值变更留痕入口，并写台账留痕。
     * 收紧后博物馆文物修复用户即使换调用地址，估值变更也在服务入口被拒绝。
     */
    @PutMapping("/valuation-change")
    public ApiResponse<PermissionPolicy> updateValuationChange(
            @Valid @RequestBody PolicyUpdateRequest request) {
        boolean wasOpen = permissionService.isValuationChangeOpen();
        PermissionPolicy policy = permissionService.updateValuationChangeOpen(
                request.getOpen(), request.getOperator(), request.getReason());

        if (wasOpen != request.getOpen()) {
            String operator = request.getOperator() == null ? "SYSTEM" : request.getOperator();
            String summary = (request.getOpen()
                    ? "文物保险估值变更留痕入口放开，估值登记/修订/审批恢复推进"
                    : "博物馆文物修复用户缺权，文物保险估值变更留痕入口收紧，"
                        + "一切估值变更在入口拒绝，换调用地址也不放行，"
                        + "已生效版本/保险批单/借展签约快照只读保留")
                    + "，操作人：" + operator
                    + (request.getReason() != null && !request.getReason().isEmpty()
                            ? "，原因：" + request.getReason() : "");
            ledgerService.append(GLOBAL_SCOPE, null, null,
                    request.getOpen() ? ACTION_VAL_POLICY_RELAX : ACTION_VAL_POLICY_TIGHTEN,
                    "POLICY#1", summary, LocalDateTime.now());
        }
        return ApiResponse.ok(policy);
    }
}
