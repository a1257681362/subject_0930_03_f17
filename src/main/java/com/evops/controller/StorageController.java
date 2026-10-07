package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.CancelRequest;
import com.evops.dto.StorageAreaCreateRequest;
import com.evops.dto.StorageCellCreateRequest;
import com.evops.dto.StorageMoveStartRequest;
import com.evops.dto.StorageRackCreateRequest;
import com.evops.dto.StorageReservationCreateRequest;
import com.evops.entity.StorageArea;
import com.evops.entity.StorageCell;
import com.evops.entity.StorageMoveOrder;
import com.evops.entity.StorageRack;
import com.evops.entity.StorageReservation;
import com.evops.service.StorageLocationService;
import com.evops.service.StorageReservationExpireScheduler;
import com.evops.service.StorageReservationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 文物库房库位与密集架预约（museum-7）：
 * 库区/密集架/格位按温区、防火等级、承重维护；搬入前预约目标格位；
 * 互斥密集架同时段单一操作；预约超时自动释放（不释放已开工格位）；
 * 库存查询按馆员权限隐藏敏感位置。
 */
@RestController
public class StorageController {

    private final StorageLocationService locationService;
    private final StorageReservationService reservationService;
    private final StorageReservationExpireScheduler expireScheduler;

    public StorageController(StorageLocationService locationService,
                             StorageReservationService reservationService,
                             StorageReservationExpireScheduler expireScheduler) {
        this.locationService = locationService;
        this.reservationService = reservationService;
        this.expireScheduler = expireScheduler;
    }

    // ---- 库区 ----

    @PostMapping("/api/storage/areas")
    public ApiResponse<StorageArea> createArea(@Valid @RequestBody StorageAreaCreateRequest request) {
        return ApiResponse.ok(locationService.createArea(request));
    }

    @GetMapping("/api/storage/areas")
    public ApiResponse<List<StorageArea>> listAreas(@RequestParam(required = false) String status) {
        return ApiResponse.ok(locationService.listAreas(status));
    }

    @PostMapping("/api/storage/areas/{id}/close")
    public ApiResponse<StorageArea> closeArea(@PathVariable Long id) {
        return ApiResponse.ok(locationService.closeArea(id));
    }

    // ---- 密集架（互斥设备） ----

    @PostMapping("/api/storage/racks")
    public ApiResponse<StorageRack> createRack(@Valid @RequestBody StorageRackCreateRequest request) {
        return ApiResponse.ok(locationService.createRack(request));
    }

    @GetMapping("/api/storage/racks")
    public ApiResponse<List<StorageRack>> listRacks(@RequestParam(required = false) Long areaId) {
        return ApiResponse.ok(locationService.listRacks(areaId));
    }

    // ---- 格位 ----

    @PostMapping("/api/storage/cells")
    public ApiResponse<StorageCell> createCell(@Valid @RequestBody StorageCellCreateRequest request) {
        return ApiResponse.ok(locationService.createCell(request));
    }

    @GetMapping("/api/storage/cells")
    public ApiResponse<List<StorageCell>> listCells(@RequestParam(required = false) Long areaId,
                                                    @RequestParam(required = false) Long rackId) {
        return ApiResponse.ok(locationService.listCells(areaId, rackId));
    }

    @PostMapping("/api/storage/cells/{id}/disable")
    public ApiResponse<StorageCell> disableCell(@PathVariable Long id) {
        return ApiResponse.ok(locationService.disableCell(id));
    }

    // ---- 预约与移动 ----

    /** 预约目标格位；同一 requestNo 补传/补录/重试只返回同一条可用结果 */
    @PostMapping("/api/storage/reservations")
    public ApiResponse<StorageReservation> reserve(@Valid @RequestBody StorageReservationCreateRequest request) {
        return ApiResponse.ok(reservationService.reserve(request));
    }

    @GetMapping("/api/storage/reservations")
    public ApiResponse<List<StorageReservation>> listReservations(@RequestParam(required = false) String status,
                                                                  @RequestParam(required = false) String requestNo,
                                                                  @RequestParam(required = false) Long cellId) {
        return ApiResponse.ok(reservationService.listReservations(status, requestNo, cellId));
    }

    @GetMapping("/api/storage/reservations/{id}")
    public ApiResponse<Map<String, Object>> reservationDetail(@PathVariable Long id) {
        return ApiResponse.ok(reservationService.detail(id));
    }

    /** 开工移动：登记操作人员，RESERVED -> MOVING */
    @PostMapping("/api/storage/reservations/{id}/start")
    public ApiResponse<StorageMoveOrder> start(@PathVariable Long id,
                                               @Valid @RequestBody StorageMoveStartRequest request) {
        return ApiResponse.ok(reservationService.start(id, request.getOperator()));
    }

    /** 完成移动：MOVING -> COMPLETED，库存清原格立目的格 */
    @PostMapping("/api/storage/reservations/{id}/complete")
    public ApiResponse<StorageMoveOrder> complete(@PathVariable Long id) {
        return ApiResponse.ok(reservationService.complete(id));
    }

    /** 取消预约（已开工不可取消） */
    @PostMapping("/api/storage/reservations/{id}/cancel")
    public ApiResponse<StorageReservation> cancel(@PathVariable Long id,
                                                  @Valid @RequestBody CancelRequest request) {
        return ApiResponse.ok(reservationService.cancel(id, request.getReason()));
    }

    /** 手动触发一轮超时释放扫描（定时任务同口径，幂等） */
    @PostMapping("/api/storage/reservations/expire-scan")
    public ApiResponse<Map<String, Integer>> expireScan() {
        int released = expireScheduler.scanOnce();
        java.util.Map<String, Integer> data = new java.util.LinkedHashMap<>();
        data.put("released", released);
        return ApiResponse.ok(data);
    }

    @GetMapping("/api/storage/move-orders")
    public ApiResponse<List<StorageMoveOrder>> listMoveOrders(@RequestParam(required = false) Long rackId,
                                                              @RequestParam(required = false) String boxNo) {
        return ApiResponse.ok(reservationService.listMoveOrders(rackId, boxNo));
    }

    // ---- 库存查询（按馆员权限隐藏敏感位置） ----

    @GetMapping("/api/storage/stock")
    public ApiResponse<List<Map<String, Object>>> stock(@RequestParam(required = false) String role,
                                                        @RequestParam(required = false) Long areaId) {
        return ApiResponse.ok(reservationService.queryStock(role, areaId));
    }
}
