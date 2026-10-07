package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.EventHandleRequest;
import com.evops.dto.EventReviewRequest;
import com.evops.dto.ReadingSubmitRequest;
import com.evops.entity.EnvEvent;
import com.evops.entity.EnvEventPhoto;
import com.evops.entity.EnvLedger;
import com.evops.entity.InspectionTask;
import com.evops.service.EnvLedgerService;
import com.evops.service.InspectionService;
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
 * 展厅环境巡检：
 * 任务按展厅定时/手动生成 -> 提交点位读数（按任务规则版本快照判定）
 * -> 异常事件处置（现场照片+处置人）-> 保管员复核关闭。
 * 报表（任务/事件/环境台账）均标明采用的规则版本。
 */
@RestController
public class InspectionController {

    private final InspectionService inspectionService;
    private final EnvLedgerService ledgerService;

    public InspectionController(InspectionService inspectionService,
                                EnvLedgerService ledgerService) {
        this.inspectionService = inspectionService;
        this.ledgerService = ledgerService;
    }

    /** 手动触发一轮任务生成（force=true 立即为每个开放展厅补一轮，幂等） */
    @PostMapping("/api/inspection/tasks/generate")
    public ApiResponse<List<InspectionTask>> generate(
            @RequestParam(required = false, defaultValue = "true") boolean force) {
        return ApiResponse.ok(inspectionService.generateDue(force));
    }

    /** 任务列表：可按展厅/状态过滤 */
    @GetMapping("/api/inspection/tasks")
    public ApiResponse<List<InspectionTask>> listTasks(@RequestParam(required = false) Long hallId,
                                                       @RequestParam(required = false) String status) {
        return ApiResponse.ok(inspectionService.listTasks(hallId, status));
    }

    /** 提交一轮巡检读数（覆盖该展厅全部在展点位）；任务完成即封存不可改写 */
    @PostMapping("/api/inspection/tasks/{id}/readings")
    public ApiResponse<InspectionTask> submitReadings(@PathVariable Long id,
                                                      @Valid @RequestBody ReadingSubmitRequest request) {
        return ApiResponse.ok(inspectionService.submitReadings(id, request));
    }

    /** 任务报表：任务头 + 读数，均标明规则版本与判定阈值 */
    @GetMapping("/api/inspection/tasks/{id}/report")
    public ApiResponse<Map<String, Object>> taskReport(@PathVariable Long id) {
        return ApiResponse.ok(inspectionService.taskReport(id));
    }

    /** 异常事件列表：可按展厅/状态/点位/材质过滤 */
    @GetMapping("/api/env-events")
    public ApiResponse<List<EnvEvent>> listEvents(@RequestParam(required = false) Long hallId,
                                                  @RequestParam(required = false) String status,
                                                  @RequestParam(required = false) String pointCode,
                                                  @RequestParam(required = false) String material) {
        return ApiResponse.ok(inspectionService.listEvents(hallId, status, pointCode, material));
    }

    /** 事件报表：事件头（标明首警规则版本）+ 现场照片 */
    @GetMapping("/api/env-events/{id}")
    public ApiResponse<Map<String, Object>> eventReport(@PathVariable Long id) {
        return ApiResponse.ok(inspectionService.eventReport(id));
    }

    /** 登记处置人与处置说明（系统停留待处理直至保管员复核） */
    @PostMapping("/api/env-events/{id}/handle")
    public ApiResponse<EnvEvent> handle(@PathVariable Long id,
                                        @Valid @RequestBody EventHandleRequest request) {
        return ApiResponse.ok(inspectionService.handle(id, request));
    }

    /** 关联异常现场照片（内容寻址去重，关闭前至少一张） */
    @PostMapping(value = "/api/env-events/{id}/photos",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<EnvEventPhoto> uploadPhoto(@PathVariable Long id,
                                                  @RequestParam("file") MultipartFile file,
                                                  @RequestParam String uploadedBy,
                                                  @RequestParam(required = false) String remark) {
        return ApiResponse.ok(inspectionService.uploadEventPhoto(id, file, uploadedBy, remark));
    }

    /**
     * 保管员复核：approved=true 且已登记处置人并关联现场照片时关闭；
     * approved=false 退回处置中，事件继续停留待处理。
     */
    @PostMapping("/api/env-events/{id}/review")
    public ApiResponse<EnvEvent> review(@PathVariable Long id,
                                        @Valid @RequestBody EventReviewRequest request) {
        return ApiResponse.ok(inspectionService.review(id, request));
    }

    /**
     * 环境台账（可追溯）：规则发布/任务生成/读数/事件开闭处置复核全留痕。
     * 不传 hallId 时返回全馆台账。
     */
    @GetMapping("/api/env-ledgers")
    public ApiResponse<List<EnvLedger>> ledgers(@RequestParam(required = false) Long hallId) {
        return ApiResponse.ok(ledgerService.listByHall(hallId));
    }
}
