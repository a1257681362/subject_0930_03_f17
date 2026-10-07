package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.AgreementAmendRequest;
import com.evops.dto.AgreementRegisterRequest;
import com.evops.dto.AgreementSignRequest;
import com.evops.dto.AnomalyResolveRequest;
import com.evops.dto.EscortNodeRequest;
import com.evops.dto.ReturnCheckRequest;
import com.evops.entity.EscortNode;
import com.evops.entity.LoanAgreement;
import com.evops.entity.LoanAgreementVersion;
import com.evops.entity.LoanReturnCheck;
import com.evops.service.LoanExhibitionService;
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
 * 文物借展协议与押运闭环（museum-6）：
 * 协议登记/签署/变更（版本只追加）-> 封箱/交接/到馆/开箱押运节点（异常暂停、开箱双人见证）
 * -> 归还逐项验收（差异保留）；另提供修复侧按项目反查借展原单与押运处理人/异常原因。
 */
@RestController
@RequestMapping("/api")
public class LoanAgreementController {

    private final LoanExhibitionService loanExhibitionService;

    public LoanAgreementController(LoanExhibitionService loanExhibitionService) {
        this.loanExhibitionService = loanExhibitionService;
    }

    /** 协议登记：借展方、保险金额、运输路线、归还日期 + 借展清单 */
    @PostMapping("/loan-agreements")
    public ApiResponse<LoanAgreement> register(@Valid @RequestBody AgreementRegisterRequest request) {
        return ApiResponse.ok(loanExhibitionService.register(request));
    }

    /** 协议列表（可按文物/状态过滤） */
    @GetMapping("/loan-agreements")
    public ApiResponse<List<LoanAgreement>> list(
            @RequestParam(value = "relicId", required = false) Long relicId,
            @RequestParam(value = "status", required = false) String status) {
        return ApiResponse.ok(loanExhibitionService.list(relicId, status));
    }

    /**
     * 修复侧反查：由修复项目定位文物的借展协议原单、押运节点处理人及异常原因。
     * 字面量路径须放在 /loan-agreements/{id} 之前声明，Spring 优先精确匹配。
     */
    @GetMapping("/loan-agreements/trace")
    public ApiResponse<Map<String, Object>> trace(@RequestParam("projectId") Long projectId) {
        return ApiResponse.ok(loanExhibitionService.traceByProject(projectId));
    }

    /** 协议详情：主单 + 版本（含清单）+ 押运节点 + 归还验收（含逐行差异） */
    @GetMapping("/loan-agreements/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(loanExhibitionService.detail(id));
    }

    /** 协议签署：仅当前 DRAFT 版本可签，已签协议不能覆盖 */
    @PostMapping("/loan-agreements/{id}/sign")
    public ApiResponse<LoanAgreementVersion> sign(@PathVariable Long id,
                                                  @Valid @RequestBody AgreementSignRequest request) {
        return ApiResponse.ok(loanExhibitionService.sign(id, request));
    }

    /** 协议变更：另出新版本（待签），旧版本与已签内容保留 */
    @PostMapping("/loan-agreements/{id}/amend")
    public ApiResponse<LoanAgreementVersion> amend(@PathVariable Long id,
                                                   @Valid @RequestBody AgreementAmendRequest request) {
        return ApiResponse.ok(loanExhibitionService.amend(id, request));
    }

    /** 押运节点登记：封箱/交接/到馆/开箱按序；异常即暂停后续节点；开箱双人见证 */
    @PostMapping("/loan-agreements/{id}/escort-nodes")
    public ApiResponse<EscortNode> registerNode(@PathVariable Long id,
                                                @Valid @RequestBody EscortNodeRequest request) {
        return ApiResponse.ok(loanExhibitionService.registerNode(id, request));
    }

    /** 押运异常解除：解除后后续节点方可继续 */
    @PostMapping("/escort-nodes/{nodeId}/resolve")
    public ApiResponse<EscortNode> resolveAnomaly(@PathVariable Long nodeId,
                                                  @Valid @RequestBody AnomalyResolveRequest request) {
        return ApiResponse.ok(loanExhibitionService.resolveAnomaly(nodeId, request));
    }

    /** 归还验收：与原借展清单逐项比对，差异逐行保留 */
    @PostMapping("/loan-agreements/{id}/return-check")
    public ApiResponse<LoanReturnCheck> returnCheck(@PathVariable Long id,
                                                    @Valid @RequestBody ReturnCheckRequest request) {
        return ApiResponse.ok(loanExhibitionService.returnCheck(id, request));
    }
}
