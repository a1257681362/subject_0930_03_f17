package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.PackSpecPublishRequest;
import com.evops.dto.PackUnpackRequest;
import com.evops.dto.PackCreateRequest;
import com.evops.dto.SubstituteApproveRequest;
import com.evops.dto.SubstitutePackRequest;
import com.evops.entity.PackLedger;
import com.evops.entity.PackOrder;
import com.evops.entity.PackPhoto;
import com.evops.entity.PackSpecVersion;
import com.evops.service.PackLedgerService;
import com.evops.service.PackagingService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 文物运输包装规格（museum-13）：
 * 规格版本化发布、装箱校验重量/外形/防震、临时替代审批留痕、装箱照片绑箱号、
 * 拆箱回写不覆盖发运前证据、外部修复依赖无响应不假装成功且恢复不重复通知。
 */
@RestController
public class PackagingController {

    private final PackagingService packagingService;
    private final PackLedgerService ledgerService;

    public PackagingController(PackagingService packagingService,
                               PackLedgerService ledgerService) {
        this.packagingService = packagingService;
        this.ledgerService = ledgerService;
    }

    // ---- 包装规格 ----

    /** 发布包装规格新版本（旧生效版本自动 SUPERSEDED） */
    @PostMapping("/api/pack-specs/publish")
    public ApiResponse<PackSpecVersion> publishSpec(@Valid @RequestBody PackSpecPublishRequest req) {
        return ApiResponse.ok(packagingService.publishSpec(req));
    }

    /** 规格版本列表 */
    @GetMapping("/api/pack-specs")
    public ApiResponse<List<PackSpecVersion>> listSpecs() {
        return ApiResponse.ok(packagingService.listVersions());
    }

    /** 当前生效规格版本 */
    @GetMapping("/api/pack-specs/active")
    public ApiResponse<PackSpecVersion> activeSpec() {
        return ApiResponse.ok(packagingService.requireActiveVersion());
    }

    /** 规格版本详情（版本 + 材质/尺寸/缓冲/气候区条目） */
    @GetMapping("/api/pack-specs/{id}")
    public ApiResponse<Map<String, Object>> specDetail(@PathVariable Long id) {
        return ApiResponse.ok(packagingService.versionDetail(id));
    }

    // ---- 装箱 ----

    /** 按规格装箱（校验重量/外形/防震；固化规格快照；外部确认无响应则停留待确认） */
    @PostMapping("/api/pack-orders")
    public ApiResponse<Map<String, Object>> packStandard(@Valid @RequestBody PackCreateRequest req) {
        return ApiResponse.ok(packagingService.packStandard(req));
    }

    /** 临时替代包装申请（必须审批，逐项记录与标准规格差异） */
    @PostMapping("/api/pack-orders/substitute")
    public ApiResponse<PackOrder> applySubstitute(@Valid @RequestBody SubstitutePackRequest req) {
        return ApiResponse.ok(packagingService.applySubstitute(req));
    }

    /** 替代包装审批：批准进入外部确认 / 驳回装箱单终态 REJECTED（驳回必填意见） */
    @PostMapping("/api/pack-substitutes/{id}/approve")
    public ApiResponse<Map<String, Object>> approveSubstitute(
            @PathVariable Long id, @Valid @RequestBody SubstituteApproveRequest req) {
        return ApiResponse.ok(packagingService.approveSubstitute(id, req));
    }

    /** 装箱单列表：可按文物/押运单/状态/箱号过滤 */
    @GetMapping("/api/pack-orders")
    public ApiResponse<List<PackOrder>> listOrders(
            @RequestParam(required = false) Long relicId,
            @RequestParam(required = false) Long agreementId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String boxNo) {
        return ApiResponse.ok(packagingService.listOrders(relicId, agreementId, status, boxNo));
    }

    /** 装箱单详情：单头（规格快照）+ 替代审批 + 发运前/拆箱后照片 + 外部通知状态 */
    @GetMapping("/api/pack-orders/{id}")
    public ApiResponse<Map<String, Object>> orderDetail(@PathVariable Long id) {
        return ApiResponse.ok(packagingService.detail(id));
    }

    /**
     * 上传装箱照片（与箱号绑定）：
     * stage=BEFORE_SHIP 发运前证据 / AFTER_UNPACK 拆箱后证据（内容寻址去重，两阶段分别只追加）。
     */
    @PostMapping(value = "/api/pack-orders/{id}/photos",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<PackPhoto> uploadPhoto(@PathVariable Long id,
                                              @RequestParam String stage,
                                              @RequestParam("file") MultipartFile file,
                                              @RequestParam String uploadedBy,
                                              @RequestParam(required = false) String remark) {
        return ApiResponse.ok(packagingService.uploadPhoto(id, stage, file, uploadedBy, remark));
    }

    /** 拆箱包装状态回写（只写拆箱字段，不覆盖发运前证据；受损必填说明） */
    @PostMapping("/api/pack-orders/{id}/unpack")
    public ApiResponse<PackOrder> unpack(@PathVariable Long id,
                                         @Valid @RequestBody PackUnpackRequest req) {
        return ApiResponse.ok(packagingService.unpack(id, req));
    }

    /** 手动补偿投递外部修复依赖通知（恢复后按行认领，只通知一次） */
    @PostMapping("/api/pack-orders/{id}/notify-retry")
    public ApiResponse<Map<String, Object>> retryNotify(@PathVariable Long id) {
        return ApiResponse.ok(packagingService.retryNotify(id));
    }

    // ---- 台账 ----

    /** 包装台账（只追加）：规格发布/装箱/替代审批/外部通知/拆箱全留痕 */
    @GetMapping("/api/pack-ledgers")
    public ApiResponse<List<PackLedger>> ledgers(@RequestParam(required = false) Long relicId,
                                                 @RequestParam(required = false) Long orderId) {
        return ApiResponse.ok(ledgerService.list(relicId, orderId));
    }
}
