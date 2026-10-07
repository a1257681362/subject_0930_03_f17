package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.BorrowCompensationResolveRequest;
import com.evops.dto.GradeCompensationResolveRequest;
import com.evops.entity.BorrowCompensation;
import com.evops.entity.RepairGradeCompensation;
import com.evops.service.RepairCompensationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/**
 * 修复部分成功补偿（museum-10）：保护等级补偿与借阅补偿成对开立、互持 id，
 * 借阅补偿先闭环后保护等级补偿方可确认；借阅补偿保险金额按查看人馆区脱敏。
 */
@RestController
@RequestMapping("/api")
public class CompensationController {

    private final RepairCompensationService compensationService;

    public CompensationController(RepairCompensationService compensationService) {
        this.compensationService = compensationService;
    }

    @GetMapping("/repair-grade-compensations")
    public ApiResponse<List<RepairGradeCompensation>> listGrade(
            @RequestParam(value = "relicId", required = false) Long relicId,
            @RequestParam(value = "status", required = false) String status) {
        return ApiResponse.ok(compensationService.listGradeComps(relicId, status));
    }

    @GetMapping("/repair-grade-compensations/{id}")
    public ApiResponse<RepairGradeCompensation> getGrade(@PathVariable Long id) {
        return ApiResponse.ok(compensationService.requireGradeCompensation(id));
    }

    /** 保护等级补偿闭环确认（成对借阅补偿须先闭环） */
    @PostMapping("/repair-grade-compensations/{id}/resolve")
    public ApiResponse<RepairGradeCompensation> resolveGrade(
            @PathVariable Long id,
            @Valid @RequestBody GradeCompensationResolveRequest request) {
        return ApiResponse.ok(compensationService.resolveGradeCompensation(id, request));
    }

    /** 借阅补偿列表（viewerCampus 与文物馆区不一致时保险金额脱敏） */
    @GetMapping("/borrow-compensations")
    public ApiResponse<List<BorrowCompensation>> listBorrow(
            @RequestParam(value = "relicId", required = false) Long relicId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "viewerCampus", required = false) String viewerCampus) {
        return ApiResponse.ok(compensationService.listBorrowComps(relicId, status, viewerCampus));
    }

    @GetMapping("/borrow-compensations/{id}")
    public ApiResponse<BorrowCompensation> getBorrow(
            @PathVariable Long id,
            @RequestParam(value = "viewerCampus", required = false) String viewerCampus) {
        // 单查同样按馆区脱敏：未声明馆区或非同馆区不返回任何保险数字
        return ApiResponse.ok(compensationService.getBorrowCompensationMasked(id, viewerCampus));
    }

    /** 借阅补偿闭环：RECALL 须已收回文物，SUPPLEMENT_CONFIRM 补充确认等级/保险差异 */
    @PostMapping("/borrow-compensations/{id}/resolve")
    public ApiResponse<BorrowCompensation> resolveBorrow(
            @PathVariable Long id,
            @Valid @RequestBody BorrowCompensationResolveRequest request) {
        return ApiResponse.ok(compensationService.resolveBorrowCompensation(id, request));
    }
}
