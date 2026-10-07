package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.PestMonthFreezeRequest;
import com.evops.dto.PestRecordSubmitRequest;
import com.evops.dto.PestTaskCloseRequest;
import com.evops.entity.PestDisposeTask;
import com.evops.entity.PestLedger;
import com.evops.entity.PestMonitorPhoto;
import com.evops.entity.PestMonitorRecord;
import com.evops.entity.PestMonthFreeze;
import com.evops.service.PestLedgerService;
import com.evops.service.PestMonitorService;
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
 * 虫害监测：
 * 外包人员只能提交现场数据（监测记录/照片），不能关闭处置任务；
 * 超阈值生成处置任务并关联责任区域；监测结果按月份冻结；
 * 报表标明规则版本，规则调整只生成新版本不改写旧结论。
 */
@RestController
public class PestMonitorController {

    private final PestMonitorService monitorService;
    private final PestLedgerService ledgerService;

    public PestMonitorController(PestMonitorService monitorService,
                                 PestLedgerService ledgerService) {
        this.monitorService = monitorService;
        this.ledgerService = ledgerService;
    }

    /** 提交监测记录（外包/馆内均可）；超阈值即生成处置任务并关联责任区域 */
    @PostMapping("/api/pest-records")
    public ApiResponse<PestMonitorRecord> submit(@Valid @RequestBody PestRecordSubmitRequest request) {
        return ApiResponse.ok(monitorService.submit(request));
    }

    /** 监测记录列表：可按点位/月份/结论过滤 */
    @GetMapping("/api/pest-records")
    public ApiResponse<List<PestMonitorRecord>> listRecords(
            @RequestParam(required = false) Long pointId,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String conclusion) {
        return ApiResponse.ok(monitorService.listRecords(pointId, month, conclusion));
    }

    /** 监测记录报表：记录头（标明规则版本）+ 虫种明细（阈值快照）+ 现场照片 */
    @GetMapping("/api/pest-records/{id}")
    public ApiResponse<Map<String, Object>> recordReport(@PathVariable Long id) {
        return ApiResponse.ok(monitorService.recordReport(id));
    }

    /** 上传监测现场照片（内容寻址去重；月份冻结后拒收） */
    @PostMapping(value = "/api/pest-records/{id}/photos",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<PestMonitorPhoto> uploadPhoto(@PathVariable Long id,
                                                     @RequestParam("file") MultipartFile file,
                                                     @RequestParam String uploadedBy,
                                                     @RequestParam(required = false) String remark) {
        return ApiResponse.ok(monitorService.uploadPhoto(id, file, uploadedBy, remark));
    }

    /** 处置任务列表：可按点位/状态/责任区域过滤 */
    @GetMapping("/api/pest-tasks")
    public ApiResponse<List<PestDisposeTask>> listTasks(
            @RequestParam(required = false) Long pointId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String responsibleArea) {
        return ApiResponse.ok(monitorService.listTasks(pointId, status, responsibleArea));
    }

    /** 关闭处置任务：仅馆内人员可关闭，外包人员只能提交现场数据不能关闭任务 */
    @PostMapping("/api/pest-tasks/{id}/close")
    public ApiResponse<PestDisposeTask> closeTask(@PathVariable Long id,
                                                  @Valid @RequestBody PestTaskCloseRequest request) {
        return ApiResponse.ok(monitorService.closeTask(id, request));
    }

    /** 冻结监测月份：冻结后该月监测记录与照片不再接受补录/更改（幂等） */
    @PostMapping("/api/pest-monitor/freeze")
    public ApiResponse<PestMonthFreeze> freeze(@Valid @RequestBody PestMonthFreezeRequest request) {
        return ApiResponse.ok(monitorService.freezeMonth(request));
    }

    /** 已冻结月份列表 */
    @GetMapping("/api/pest-monitor/freezes")
    public ApiResponse<List<PestMonthFreeze>> freezes() {
        return ApiResponse.ok(monitorService.listFreezes());
    }

    /** 虫害台账（只追加）：规则发布/点位/诱捕片/监测/任务/冻结全留痕 */
    @GetMapping("/api/pest-ledgers")
    public ApiResponse<List<PestLedger>> ledgers(@RequestParam(required = false) Long pointId) {
        return ApiResponse.ok(ledgerService.list(pointId));
    }
}
