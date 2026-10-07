package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.IntakeRequest;
import com.evops.dto.RelicCreateRequest;
import com.evops.dto.RelicQuery;
import com.evops.dto.RevokeRequest;
import com.evops.entity.Relic;
import com.evops.entity.RelicIntake;
import com.evops.service.IntakeService;
import com.evops.service.ImageArchiveService;
import com.evops.service.LedgerService;
import com.evops.service.LoanService;
import com.evops.service.RelicService;
import com.evops.service.RepairService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文物档案 + 入藏登记 + 台账流水。
 */
@RestController
@RequestMapping("/api/relics")
public class RelicController {

    private final RelicService relicService;
    private final IntakeService intakeService;
    private final LoanService loanService;
    private final RepairService repairService;
    private final ImageArchiveService imageArchiveService;
    private final LedgerService ledgerService;

    public RelicController(RelicService relicService,
                           IntakeService intakeService,
                           LoanService loanService,
                           RepairService repairService,
                           ImageArchiveService imageArchiveService,
                           LedgerService ledgerService) {
        this.relicService = relicService;
        this.intakeService = intakeService;
        this.loanService = loanService;
        this.repairService = repairService;
        this.imageArchiveService = imageArchiveService;
        this.ledgerService = ledgerService;
    }

    /** 建档：材质、年代、来源、保存等级；文物编号重复会被拒绝 */
    @PostMapping
    public ApiResponse<Relic> create(@Valid @RequestBody RelicCreateRequest request) {
        return ApiResponse.ok(relicService.create(request));
    }

    /** 档案列表，可按状态/关键字过滤 */
    @GetMapping
    public ApiResponse<?> list(RelicQuery query) {
        return ApiResponse.ok(relicService.list(query));
    }

    /** 文物完整档案：基本信息 + 入藏(含照片) + 借还 + 修复项目 + 影像版本 + 台账流水 */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> get(
            @PathVariable Long id,
            @RequestParam(value = "role", required = false) String role) {
        Relic relic = relicService.requireRelic(id);
        RelicIntake intake = intakeService.getByRelic(id);

        Map<String, Object> dossier = new LinkedHashMap<>();
        dossier.put("relic", relic);
        if (intake != null) {
            Map<String, Object> intakeBundle = new LinkedHashMap<>();
            intakeBundle.put("intake", intake);
            intakeBundle.put("photos", intakeService.listPhotos(intake.getId()));
            dossier.put("intake", intakeBundle);
        }
        dossier.put("loans", loanService.listByRelic(id));
        dossier.put("repairProjects", repairService.listByRelic(id));
        dossier.put("imageVersions", imageArchiveService.listVersions(id, role));
        dossier.put("ledger", ledgerService.listByRelic(id));
        return ApiResponse.ok(dossier);
    }

    /** 撤销：只改状态不抹除记录 */
    @PostMapping("/{id}/revoke")
    public ApiResponse<Relic> revoke(@PathVariable Long id,
                                     @Valid @RequestBody RevokeRequest request) {
        return ApiResponse.ok(relicService.revoke(id, request.getReason()));
    }

    /** 入藏登记：关联照片、尺寸、库位；一件文物只能入藏一次 */
    @PostMapping("/{id}/intake")
    public ApiResponse<RelicIntake> intake(@PathVariable Long id,
                                           @Valid @RequestBody IntakeRequest request) {
        return ApiResponse.ok(intakeService.register(id, request));
    }

    /** 台账流水：入藏/借出/归还/修复/验收/撤销的完整动作链 */
    @GetMapping("/{id}/ledger")
    public ApiResponse<?> ledger(@PathVariable Long id) {
        relicService.requireRelic(id);
        return ApiResponse.ok(ledgerService.listByRelic(id));
    }
}
