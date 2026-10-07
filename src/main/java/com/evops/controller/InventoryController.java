package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.InvDiffCreateRequest;
import com.evops.dto.InvDiffReviewRequest;
import com.evops.dto.InvRebaselineRequest;
import com.evops.dto.InvScanRequest;
import com.evops.dto.InvSupplementRequest;
import com.evops.dto.InvTaskCloseRequest;
import com.evops.dto.InvTaskGenerateRequest;
import com.evops.entity.InvDiffOrder;
import com.evops.entity.InvDiffPhoto;
import com.evops.entity.InvLedger;
import com.evops.entity.InvScan;
import com.evops.entity.InvSupplement;
import com.evops.entity.InvTask;
import com.evops.service.InvDiffService;
import com.evops.service.InvLedgerService;
import com.evops.service.InventoryService;
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
 * 展览借品清点盘点：按展厅生成清点任务，扫描箱号与展签列出缺失/错位/状态异常；
 * 差异单引用现场照片与见证人且必须能解释，复核完成前不能关闭展览；
 * 清点期间借还变更提示版本冲突，重基线回放旧数据生成新版本；
 * 已关闭任务只能新增补充记录，不能重写原结果。
 */
@RestController
public class InventoryController {

    private final InventoryService inventoryService;
    private final InvDiffService diffService;
    private final InvLedgerService ledgerService;

    public InventoryController(InventoryService inventoryService,
                               InvDiffService diffService,
                               InvLedgerService ledgerService) {
        this.inventoryService = inventoryService;
        this.diffService = diffService;
        this.ledgerService = ledgerService;
    }

    /** 按展厅生成清点任务（一展厅同时一个在途任务，重复生成幂等） */
    @PostMapping("/api/inv-tasks")
    public ApiResponse<InvTask> generate(@Valid @RequestBody InvTaskGenerateRequest request) {
        return ApiResponse.ok(inventoryService.generate(request));
    }

    /** 清点任务列表：可按展厅/状态过滤 */
    @GetMapping("/api/inv-tasks")
    public ApiResponse<List<InvTask>> listTasks(@RequestParam(required = false) Long hallId,
                                                @RequestParam(required = false) String status) {
        return ApiResponse.ok(inventoryService.listTasks(hallId, status));
    }

    /** 任务详情：任务头 + 当前版本基线行 + 扫描 + 差异单 + 补充记录 */
    @GetMapping("/api/inv-tasks/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(inventoryService.detail(id));
    }

    /** 扫描箱号与展签（借还/展陈变更后提示版本冲突） */
    @PostMapping("/api/inv-tasks/{id}/scans")
    public ApiResponse<InvScan> scan(@PathVariable Long id,
                                     @Valid @RequestBody InvScanRequest request) {
        return ApiResponse.ok(inventoryService.scan(id, request));
    }

    /** 差异清单：系统列出当前版本的缺失、错位和状态异常 */
    @GetMapping("/api/inv-tasks/{id}/diff-report")
    public ApiResponse<Map<String, Object>> diffReport(@PathVariable Long id) {
        return ApiResponse.ok(inventoryService.diffReport(id));
    }

    /** 重基线：旧数据回放后提交新版本（版本冲突后的推进方式） */
    @PostMapping("/api/inv-tasks/{id}/rebaseline")
    public ApiResponse<InvTask> rebaseline(@PathVariable Long id,
                                           @Valid @RequestBody InvRebaselineRequest request) {
        return ApiResponse.ok(inventoryService.rebaseline(id, request));
    }

    /** 关闭清点任务：差异全部复核完成后方可关闭 */
    @PostMapping("/api/inv-tasks/{id}/close")
    public ApiResponse<InvTask> close(@PathVariable Long id,
                                      @Valid @RequestBody InvTaskCloseRequest request) {
        return ApiResponse.ok(inventoryService.close(id, request));
    }

    /** 补充记录：已关闭任务唯一允许的新增（原结果不重写） */
    @PostMapping("/api/inv-tasks/{id}/supplements")
    public ApiResponse<InvSupplement> supplement(@PathVariable Long id,
                                                 @Valid @RequestBody InvSupplementRequest request) {
        return ApiResponse.ok(inventoryService.supplement(id, request));
    }

    /** 开立差异单：差异必须能解释（说明+见证人），现场照片随后上传 */
    @PostMapping("/api/inv-tasks/{id}/diff-orders")
    public ApiResponse<InvDiffOrder> createDiff(@PathVariable Long id,
                                                @Valid @RequestBody InvDiffCreateRequest request) {
        return ApiResponse.ok(diffService.create(id, request));
    }

    /** 差异单列表：可按任务/状态过滤 */
    @GetMapping("/api/inv-diff-orders")
    public ApiResponse<List<InvDiffOrder>> listDiffs(@RequestParam(required = false) Long taskId,
                                                     @RequestParam(required = false) String status) {
        return ApiResponse.ok(diffService.listDiffs(taskId, status));
    }

    /** 差异单详情：单头 + 逐行差异 + 现场照片 */
    @GetMapping("/api/inv-diff-orders/{id}")
    public ApiResponse<Map<String, Object>> diffDetail(@PathVariable Long id) {
        return ApiResponse.ok(diffService.detail(id));
    }

    /** 上传差异单现场照片（内容寻址去重，复核前至少一张） */
    @PostMapping(value = "/api/inv-diff-orders/{id}/photos",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<InvDiffPhoto> uploadPhoto(@PathVariable Long id,
                                                 @RequestParam("file") MultipartFile file,
                                                 @RequestParam String uploadedBy,
                                                 @RequestParam(required = false) String remark) {
        return ApiResponse.ok(diffService.uploadPhoto(id, file, uploadedBy, remark));
    }

    /** 差异单复核：通过则复核完成；退回须填意见 */
    @PostMapping("/api/inv-diff-orders/{id}/review")
    public ApiResponse<InvDiffOrder> review(@PathVariable Long id,
                                            @Valid @RequestBody InvDiffReviewRequest request) {
        return ApiResponse.ok(diffService.review(id, request));
    }

    /** 清点台账（只追加）：任务/扫描/重基线/差异单/复核/关闭/补充全留痕 */
    @GetMapping("/api/inv-ledgers")
    public ApiResponse<List<InvLedger>> ledgers(@RequestParam(required = false) Long hallId) {
        return ApiResponse.ok(ledgerService.list(hallId));
    }
}
