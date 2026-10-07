package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.PestCardDeployRequest;
import com.evops.dto.PestPointCreateRequest;
import com.evops.dto.PestPointMoveRequest;
import com.evops.entity.PestPointMove;
import com.evops.entity.PestTrapCard;
import com.evops.entity.PestTrapPoint;
import com.evops.service.PestPointService;
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
 * 诱捕点位与诱捕片：
 * 点位设在展厅/库房，登记编号、布放日期、坐标与责任区域；
 * 点位迁移保留前后坐标；更换诱捕片不删除旧记录。
 */
@RestController
public class PestPointController {

    private final PestPointService pointService;

    public PestPointController(PestPointService pointService) {
        this.pointService = pointService;
    }

    /** 点位建档（展厅/库房，编号唯一） */
    @PostMapping("/api/pest-points")
    public ApiResponse<PestTrapPoint> create(@Valid @RequestBody PestPointCreateRequest request) {
        return ApiResponse.ok(pointService.create(request));
    }

    /** 点位列表：可按区域类型/区域/状态过滤 */
    @GetMapping("/api/pest-points")
    public ApiResponse<List<PestTrapPoint>> list(@RequestParam(required = false) String zoneType,
                                                 @RequestParam(required = false) Long zoneId,
                                                 @RequestParam(required = false) String status) {
        return ApiResponse.ok(pointService.listPoints(zoneType, zoneId, status));
    }

    /** 点位详情：点位 + 诱捕片（含已更换旧片）+ 迁移记录（前后坐标） */
    @GetMapping("/api/pest-points/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        PestTrapPoint point = pointService.requirePoint(id);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("point", point);
        detail.put("cards", pointService.listCards(id));
        detail.put("moves", pointService.listMoves(id));
        return ApiResponse.ok(detail);
    }

    /** 点位迁移：保留迁移前后坐标 */
    @PostMapping("/api/pest-points/{id}/move")
    public ApiResponse<PestPointMove> move(@PathVariable Long id,
                                           @Valid @RequestBody PestPointMoveRequest request) {
        return ApiResponse.ok(pointService.move(id, request));
    }

    /** 点位停用（历史保留） */
    @PostMapping("/api/pest-points/{id}/retire")
    public ApiResponse<PestTrapPoint> retire(@PathVariable Long id) {
        return ApiResponse.ok(pointService.retire(id));
    }

    /** 布放/更换诱捕片：旧片置 REPLACED 不删除，新片在布 */
    @PostMapping("/api/pest-points/{id}/cards")
    public ApiResponse<PestTrapCard> deployCard(@PathVariable Long id,
                                                @Valid @RequestBody PestCardDeployRequest request) {
        return ApiResponse.ok(pointService.deployCard(id, request));
    }

    /** 点位诱捕片列表（含已更换旧片，旧记录不删除） */
    @GetMapping("/api/pest-points/{id}/cards")
    public ApiResponse<List<PestTrapCard>> listCards(@PathVariable Long id) {
        return ApiResponse.ok(pointService.listCards(id));
    }
}
