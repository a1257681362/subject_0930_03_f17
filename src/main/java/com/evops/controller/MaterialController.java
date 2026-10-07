package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.MaterialCreateRequest;
import com.evops.dto.MaterialInboundRequest;
import com.evops.entity.Material;
import com.evops.entity.MaterialBatch;
import com.evops.service.MaterialAccountingService;
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
 * 修复材料：材料目录（颜料/黏合剂/保护剂）、按批号入库、批号库存与对账。
 */
@RestController
@RequestMapping("/api/materials")
public class MaterialController {

    private final MaterialAccountingService materialService;

    public MaterialController(MaterialAccountingService materialService) {
        this.materialService = materialService;
    }

    /** 材料建档：编号重复返回失败 */
    @PostMapping
    public ApiResponse<Material> create(@Valid @RequestBody MaterialCreateRequest request) {
        return ApiResponse.ok(materialService.createMaterial(request));
    }

    /** 材料目录，可按类别 PIGMENT/ADHESIVE/PROTECTANT 过滤 */
    @GetMapping
    public ApiResponse<List<Material>> list(@RequestParam(value = "category", required = false) String category) {
        return ApiResponse.ok(materialService.listMaterials(category));
    }

    /** 按批号入库（同批号重复入库累加库存）；过期批号允许登记但不能领用 */
    @PostMapping("/inbound")
    public ApiResponse<MaterialBatch> inbound(@Valid @RequestBody MaterialInboundRequest request) {
        return ApiResponse.ok(materialService.inbound(request));
    }

    /** 批号库存列表；usable=true 仅返回在库且未过期、可领用的批号 */
    @GetMapping("/batches")
    public ApiResponse<List<MaterialBatch>> batches(
            @RequestParam(value = "materialId", required = false) Long materialId,
            @RequestParam(value = "usable", required = false) Boolean usable) {
        return ApiResponse.ok(materialService.listBatches(materialId, usable));
    }

    /** 批号库存对账：累计入库 - 累计领用 + 累计退料 应等于现库存 */
    @GetMapping("/batches/{id}/reconcile")
    public ApiResponse<Map<String, Object>> reconcile(@PathVariable Long id) {
        return ApiResponse.ok(materialService.reconcileBatch(id));
    }
}
