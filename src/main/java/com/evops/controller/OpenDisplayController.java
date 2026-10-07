package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.OpenDisplayRevokeRequest;
import com.evops.dto.OpenDisplaySetRequest;
import com.evops.dto.SupplementCloseRequest;
import com.evops.dto.SupplementCreateRequest;
import com.evops.entity.OpenDisplayGrant;
import com.evops.entity.RepairSupplement;
import com.evops.service.OpenDisplayService;
import com.evops.service.RepairSupplementService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 开放展示标记与补修单（管理端）：
 * 开放标记只能在成果验收通过后设置；撤销留痕、记录保留；
 * 开放后发现问题开立补修单，原开放记录保留。
 */
@RestController
public class OpenDisplayController {

    private final OpenDisplayService openDisplayService;
    private final RepairSupplementService supplementService;

    public OpenDisplayController(OpenDisplayService openDisplayService,
                                 RepairSupplementService supplementService) {
        this.openDisplayService = openDisplayService;
        this.supplementService = supplementService;
    }

    /** 设置开放展示标记：须成果验收通过；登记对外摘要与授权影像子集 */
    @PostMapping("/api/repair-projects/{id}/open-display")
    public ApiResponse<OpenDisplayGrant> set(@PathVariable Long id,
                                             @Valid @RequestBody OpenDisplaySetRequest request) {
        return ApiResponse.ok(openDisplayService.setOpenDisplay(id, request));
    }

    /** 撤销开放展示标记：只置 REVOKED 留痕，记录保留 */
    @PostMapping("/api/open-displays/{grantId}/revoke")
    public ApiResponse<OpenDisplayGrant> revoke(@PathVariable Long grantId,
                                                @Valid @RequestBody OpenDisplayRevokeRequest request) {
        return ApiResponse.ok(openDisplayService.revoke(grantId, request.getReason(), request.getOperator()));
    }

    /** 项目开放记录（含已撤销） */
    @GetMapping("/api/repair-projects/{id}/open-displays")
    public ApiResponse<List<Map<String, Object>>> listByProject(@PathVariable Long id) {
        return ApiResponse.ok(openDisplayService.listByProject(id));
    }

    /** 开立补修单：开放后发现问题；原开放记录保留不动 */
    @PostMapping("/api/repair-projects/{id}/supplements")
    public ApiResponse<RepairSupplement> createSupplement(@PathVariable Long id,
                                                          @Valid @RequestBody SupplementCreateRequest request) {
        return ApiResponse.ok(supplementService.create(id, request));
    }

    /** 项目补修单列表（含已闭环） */
    @GetMapping("/api/repair-projects/{id}/supplements")
    public ApiResponse<List<RepairSupplement>> listSupplements(@PathVariable Long id) {
        return ApiResponse.ok(supplementService.listByProject(id));
    }

    /** 补修单闭环：登记处置结果，记录保留 */
    @PostMapping("/api/supplements/{id}/close")
    public ApiResponse<RepairSupplement> closeSupplement(@PathVariable Long id,
                                                         @Valid @RequestBody SupplementCloseRequest request) {
        return ApiResponse.ok(supplementService.close(id, request.getClosedBy(), request.getCloseNote()));
    }
}
