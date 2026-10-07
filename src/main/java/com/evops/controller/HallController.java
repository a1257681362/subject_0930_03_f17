package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.HallCloseRequest;
import com.evops.dto.HallCreateRequest;
import com.evops.dto.HallRelicRequest;
import com.evops.entity.Hall;
import com.evops.entity.HallRelic;
import com.evops.service.HallService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 展厅与陈展：巡检任务按展厅生成；文物上展材质决定适用规则条目。
 */
@RestController
public class HallController {

    private final HallService hallService;

    public HallController(HallService hallService) {
        this.hallService = hallService;
    }

    @PostMapping("/api/halls")
    public ApiResponse<Hall> create(@Valid @RequestBody HallCreateRequest request) {
        return ApiResponse.ok(hallService.create(request));
    }

    @GetMapping("/api/halls")
    public ApiResponse<List<Hall>> list(@RequestParam(required = false) String status) {
        return ApiResponse.ok(hallService.listHalls(status));
    }

    /** 展厅详情：展厅头 + 在展文物（材质与点位） */
    @GetMapping("/api/halls/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        Hall hall = hallService.requireHall(id);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("hall", hall);
        detail.put("onDisplay", hallService.listOnDisplay(id));
        return ApiResponse.ok(detail);
    }

    /** 文物上展厅陈展（按点位） */
    @PostMapping("/api/halls/{id}/relics")
    public ApiResponse<HallRelic> display(@PathVariable Long id,
                                          @Valid @RequestBody HallRelicRequest request) {
        return ApiResponse.ok(hallService.display(id, request));
    }

    /** 撤展：只置状态，历史读数与事件保留 */
    @PostMapping("/api/halls/{id}/relics/{relicId}/withdraw")
    public ApiResponse<HallRelic> withdraw(@PathVariable Long id, @PathVariable Long relicId) {
        return ApiResponse.ok(hallService.withdraw(id, relicId));
    }

    /** 关闭展厅（展览）：有清点中任务或未复核差异单时拒绝（复核完成前不能关闭展览） */
    @PostMapping("/api/halls/{id}/close")
    public ApiResponse<Hall> close(@PathVariable Long id,
                                   @RequestBody(required = false) HallCloseRequest request) {
        return ApiResponse.ok(hallService.close(id,
                request == null ? null : request.getClosedBy()));
    }
}
