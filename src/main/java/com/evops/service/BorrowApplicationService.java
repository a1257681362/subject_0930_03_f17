package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.dto.BorrowApplyRequest;
import com.evops.dto.BorrowApproveRequest;
import com.evops.dto.BorrowCancelRequest;
import com.evops.dto.BorrowExtensionRequest;
import com.evops.dto.BorrowLendRequest;
import com.evops.dto.BorrowReturnRequest;
import com.evops.entity.BorrowApplication;
import com.evops.entity.BorrowApprovalRecord;
import com.evops.entity.BorrowExtension;
import com.evops.entity.BorrowGradePolicy;
import com.evops.entity.BorrowSnapshot;
import com.evops.entity.HallRelic;
import com.evops.entity.LoanAgreement;
import com.evops.entity.Relic;
import com.evops.entity.RelicLoan;
import com.evops.entity.RepairProject;
import com.evops.mapper.BorrowApplicationMapper;
import com.evops.mapper.BorrowApprovalRecordMapper;
import com.evops.mapper.BorrowCompensationMapper;
import com.evops.mapper.BorrowExtensionMapper;
import com.evops.mapper.BorrowSnapshotMapper;
import com.evops.mapper.HallRelicMapper;
import com.evops.mapper.LoanAgreementMapper;
import com.evops.mapper.RelicLoanMapper;
import com.evops.mapper.RepairProjectMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.evops.constant.MuseumConstants.*;

/**
 * 文物借阅审批链路（museum-10）：
 *
 * 申请（按等级策略校验场景/时长，校验当前展陈计划与修复状态，固化策略快照）
 *   -> 按固化角色顺序逐人审批（任一驳回即终止；高风险在常规角色后追加馆长签名）
 *   -> 全部通过 APPROVED 并生成不可变借阅快照
 *   -> 批准出库 BORROWED（贯通写 t_relic_loan LENT，继续占用校验）
 *   -> 归还 RETURNED（在原单闭环）。
 *
 * 归还延期必须重新走完整审批链（高风险仍须馆长签名），驳回不改原应还时间。
 * 保险资料按馆区隔离：跨馆区查看申请/快照时保险金额脱敏（不返回数字）。
 */
@Service
public class BorrowApplicationService {

    /** 原申请审批链在 t_borrow_approval_record.extension_id 上的哨兵值 */
    public static final long ORIGINAL_CHAIN = 0L;

    private final BorrowApplicationMapper applicationMapper;
    private final BorrowApprovalRecordMapper approvalMapper;
    private final BorrowSnapshotMapper snapshotMapper;
    private final BorrowExtensionMapper extensionMapper;
    private final BorrowCompensationMapper compensationMapper;
    private final RelicLoanMapper relicLoanMapper;
    private final LoanAgreementMapper agreementMapper;
    private final HallRelicMapper hallRelicMapper;
    private final RepairProjectMapper projectMapper;
    private final BorrowPolicyService policyService;
    private final RelicService relicService;
    private final LedgerService ledgerService;
    private final ObjectMapper objectMapper;

    public BorrowApplicationService(BorrowApplicationMapper applicationMapper,
                                    BorrowApprovalRecordMapper approvalMapper,
                                    BorrowSnapshotMapper snapshotMapper,
                                    BorrowExtensionMapper extensionMapper,
                                    BorrowCompensationMapper compensationMapper,
                                    RelicLoanMapper relicLoanMapper,
                                    LoanAgreementMapper agreementMapper,
                                    HallRelicMapper hallRelicMapper,
                                    RepairProjectMapper projectMapper,
                                    BorrowPolicyService policyService,
                                    RelicService relicService,
                                    LedgerService ledgerService,
                                    ObjectMapper objectMapper) {
        this.applicationMapper = applicationMapper;
        this.approvalMapper = approvalMapper;
        this.snapshotMapper = snapshotMapper;
        this.extensionMapper = extensionMapper;
        this.compensationMapper = compensationMapper;
        this.relicLoanMapper = relicLoanMapper;
        this.agreementMapper = agreementMapper;
        this.hallRelicMapper = hallRelicMapper;
        this.projectMapper = projectMapper;
        this.policyService = policyService;
        this.relicService = relicService;
        this.ledgerService = ledgerService;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // 申请
    // ------------------------------------------------------------------

    @Transactional
    public BorrowApplication apply(Long relicId, BorrowApplyRequest req) {
        Relic relic = relicService.requireActiveRelic(relicId);

        // 跨通道占用：旧借出通道与借展协议在途都不能再申请借阅
        Long openLoans = relicLoanMapper.selectCount(new LambdaQueryWrapper<RelicLoan>()
                .eq(RelicLoan::getRelicId, relicId)
                .eq(RelicLoan::getStatus, LOAN_LENT));
        if (openLoans != null && openLoans > 0) {
            throw new IllegalArgumentException("文物仍有借出未归还，不能提交借阅申请: " + relic.getRelicNo());
        }
        Long openAgreements = agreementMapper.selectCount(new LambdaQueryWrapper<LoanAgreement>()
                .eq(LoanAgreement::getRelicId, relicId)
                .notIn(LoanAgreement::getStatus, AGR_RETURNED, AGR_CANCELLED));
        if (openAgreements != null && openAgreements > 0) {
            throw new IllegalArgumentException("文物存在未闭环借展协议，不能提交借阅申请: " + relic.getRelicNo());
        }
        // 借阅通道自身：同一文物同时只允许一条未闭环申请
        Long openApps = applicationMapper.selectCount(new LambdaQueryWrapper<BorrowApplication>()
                .eq(BorrowApplication::getRelicId, relicId)
                .in(BorrowApplication::getStatus, BAPP_PENDING, BAPP_APPROVED, BAPP_BORROWED));
        if (openApps != null && openApps > 0) {
            throw new IllegalArgumentException("文物存在未闭环借阅申请，不能重复申请: " + relic.getRelicNo());
        }

        // 当前展陈计划校验：展出中文物不能借阅
        Long onDisplay = hallRelicMapper.selectCount(new LambdaQueryWrapper<HallRelic>()
                .eq(HallRelic::getRelicId, relicId)
                .eq(HallRelic::getStatus, DISPLAY_ON));
        if (onDisplay != null && onDisplay > 0) {
            throw new IllegalArgumentException("文物在当前展陈计划中（展出中），须先撤展才能借阅: "
                    + relic.getRelicNo());
        }
        // 修复状态校验：修复中（立项/施工中）文物不能申请；
        // 待验收（REPAIRED）允许提交申请，但出库前仍须已验收闭环（lend 再校验）
        Long repairing = projectMapper.selectCount(new LambdaQueryWrapper<RepairProject>()
                .eq(RepairProject::getRelicId, relicId)
                .in(RepairProject::getStatus, PROJECT_PLANNED, PROJECT_REPAIRING));
        if (repairing != null && repairing > 0) {
            throw new IllegalArgumentException("文物存在立项/修复中的修复项目，不能借阅: "
                    + relic.getRelicNo());
        }

        // 等级策略校验：场景与最高时长
        BorrowGradePolicy policy = policyService.requirePolicy(relic.getPreservationGrade());
        if (!policyService.sceneAllowed(policy, req.getBorrowScene())) {
            throw new IllegalArgumentException("保护等级 " + relic.getPreservationGrade()
                    + " 不允许该借阅场景: " + req.getBorrowScene());
        }
        if (!req.getExpectedReturnAt().isAfter(req.getPlanLentAt())) {
            throw new IllegalArgumentException("预计归还时间必须晚于计划出库时间");
        }
        long durationDays = ChronoUnit.DAYS.between(req.getPlanLentAt(), req.getExpectedReturnAt());
        if (durationDays <= 0) {
            throw new IllegalArgumentException("借阅时长至少 1 天");
        }
        if (durationDays > policy.getMaxDurationDays()) {
            throw new IllegalArgumentException("借阅时长 " + durationDays + " 天超过保护等级 "
                    + relic.getPreservationGrade() + " 的最高允许时长 " + policy.getMaxDurationDays() + " 天");
        }

        LocalDateTime now = LocalDateTime.now();
        BorrowApplication app = new BorrowApplication();
        app.setApplicationNo(String.format("BA-%06d", applicationMapper.nextAppSeq()));
        app.setRelicId(relicId);
        app.setApplicant(req.getApplicant());
        app.setBorrower(req.getBorrower());
        app.setBorrowScene(req.getBorrowScene());
        app.setPlanLentAt(req.getPlanLentAt());
        app.setExpectedReturnAt(req.getExpectedReturnAt());
        app.setDurationDays((int) durationDays);
        app.setPurpose(req.getPurpose());
        app.setInsuranceAmount(req.getInsuranceAmount());
        app.setCampusCode(relic.getCampusCode());
        app.setPreservationGrade(relic.getPreservationGrade());
        app.setApprovalRoles(policy.getApprovalRoles());
        app.setDirectorSignRequired(policy.getDirectorSignRequired());
        app.setStatus(BAPP_PENDING);
        applicationMapper.insert(app);

        ledgerService.append(relicId, null, null,
                ACTION_BORROW_APPLY, app.getApplicationNo(),
                "借阅申请提交，借用方：" + app.getBorrower() + "，场景：" + app.getBorrowScene()
                        + "，时长 " + app.getDurationDays() + " 天，审批链："
                        + String.join("->", effectiveRoles(app.getApprovalRoles(),
                        app.getDirectorSignRequired()))
                        + (app.getDirectorSignRequired() == 1 ? "（含馆长追加签名）" : ""), now);
        return app;
    }

    // ------------------------------------------------------------------
    // 审批
    // ------------------------------------------------------------------

    /** 原申请链审批签署 */
    @Transactional
    public BorrowApprovalRecord approve(Long applicationId, BorrowApproveRequest req) {
        BorrowApplication app = requireApplication(applicationId);
        if (!BAPP_PENDING.equals(app.getStatus())) {
            throw new IllegalArgumentException("借阅申请不在审批中，不能签署: "
                    + app.getApplicationNo() + "（" + app.getStatus() + "）");
        }
        return signStep(app, ORIGINAL_CHAIN, app.getApprovalRoles(),
                app.getDirectorSignRequired(), req);
    }

    /**
     * 签署链上的下一步（原申请链与延期重审链共用）。
     * 常规角色全部同意后，高风险链再追加一步馆长（DIRECTOR）签名。
     */
    private BorrowApprovalRecord signStep(BorrowApplication app, long extensionId,
                                          String rolesCsv, int directorRequired,
                                          BorrowApproveRequest req) {
        List<String> chain = effectiveRoles(rolesCsv, directorRequired);
        List<BorrowApprovalRecord> signed = listChain(app.getId(), extensionId);
        int stepNo = signed.size() + 1;
        if (signed.size() >= chain.size()) {
            throw new IllegalArgumentException("该审批链已全部签署完成，不能重复签署");
        }
        String expectedRole = chain.get(signed.size());
        if (!expectedRole.equals(req.getApproverRole())) {
            throw new IllegalArgumentException("未按审批顺序签署：当前第 " + stepNo
                    + " 步应签角色为 " + expectedRole + "，实际为 " + req.getApproverRole());
        }
        String decision = req.getDecision();
        if (!BORROW_DECISION_APPROVE.equals(decision) && !BORROW_DECISION_REJECT.equals(decision)) {
            throw new IllegalArgumentException("审批决定只能为 APPROVE/REJECT");
        }
        if (BORROW_DECISION_REJECT.equals(decision)
                && (req.getOpinion() == null || req.getOpinion().trim().isEmpty())) {
            throw new IllegalArgumentException("驳回必须填写审批意见");
        }
        boolean directorStep = directorRequired == 1
                && signed.size() == parseRoles(rolesCsv).size();

        LocalDateTime now = LocalDateTime.now();
        BorrowApprovalRecord record = new BorrowApprovalRecord();
        record.setApplicationId(app.getId());
        record.setExtensionId(extensionId);
        record.setStepNo(stepNo);
        record.setApproverRole(expectedRole);
        record.setApproverName(req.getApproverName());
        record.setDecision(decision);
        record.setOpinion(req.getOpinion());
        record.setDirectorSign(directorStep ? 1 : 0);
        record.setSignedAt(now);
        approvalMapper.insert(record);

        if (BORROW_DECISION_REJECT.equals(decision)) {
            if (extensionId == ORIGINAL_CHAIN) {
                app.setStatus(BAPP_REJECTED);
                app.setRejectReason(req.getOpinion());
                applicationMapper.updateById(app);
                ledgerService.append(app.getRelicId(), null, null,
                        ACTION_BORROW_REJECT, app.getApplicationNo(),
                        "借阅审批第 " + stepNo + " 步被" + req.getApproverName()
                                + "（" + expectedRole + "）驳回，审批终止：" + req.getOpinion(), now);
            } else {
                BorrowExtension ext = requireExtension(extensionId);
                ext.setStatus(EXT_REJECTED);
                ext.setHandledBy(req.getApproverName());
                ext.setHandledAt(now);
                ext.setHandleOpinion(req.getOpinion());
                extensionMapper.updateById(ext);
                ledgerService.append(app.getRelicId(), null, null,
                        ACTION_BORROW_EXTEND_REJECT, ext.getExtensionNo(),
                        "延期重审第 " + stepNo + " 步被" + req.getApproverName()
                                + "（" + expectedRole + "）驳回，原应还时间不变：" + req.getOpinion(), now);
            }
            return record;
        }

        boolean lastStep = stepNo == chain.size();
        if (extensionId == ORIGINAL_CHAIN) {
            if (lastStep) {
                app.setStatus(BAPP_APPROVED);
                app.setApprovedAt(now);
                applicationMapper.updateById(app);
                generateSnapshot(app, listChain(app.getId(), ORIGINAL_CHAIN), now);
                ledgerService.append(app.getRelicId(), null, null,
                        ACTION_BORROW_APPROVE, app.getApplicationNo(),
                        "借阅审批全部通过（共 " + chain.size() + " 步"
                                + (directorRequired == 1 ? "，含馆长追加签名" : "")
                                + "），借阅快照已生成", now);
            } else {
                ledgerService.append(app.getRelicId(), null, null,
                        ACTION_BORROW_APPROVE, app.getApplicationNo(),
                        "借阅审批第 " + stepNo + "/" + chain.size() + " 步："
                                + req.getApproverName() + "（" + expectedRole + "）同意", now);
            }
        } else {
            if (lastStep) {
                BorrowExtension ext = requireExtension(extensionId);
                ext.setStatus(EXT_APPROVED);
                ext.setHandledBy(req.getApproverName());
                ext.setHandledAt(now);
                extensionMapper.updateById(ext);
                applyApprovedExtension(app, ext, now);
                ledgerService.append(app.getRelicId(), null, null,
                        ACTION_BORROW_EXTEND_APPROVE, ext.getExtensionNo(),
                        "归还延期重新审批全部通过，应还时间延至 " + ext.getRequestedReturnAt()
                                + (directorRequired == 1 ? "（含馆长追加签名）" : ""), now);
            } else {
                BorrowExtension ext = requireExtension(extensionId);
                ledgerService.append(app.getRelicId(), null, null,
                        ACTION_BORROW_APPROVE, ext.getExtensionNo(),
                        "延期重审第 " + stepNo + "/" + chain.size() + " 步："
                                + req.getApproverName() + "（" + expectedRole + "）同意", now);
            }
        }
        return record;
    }

    // ------------------------------------------------------------------
    // 出库 / 归还 / 撤销
    // ------------------------------------------------------------------

    /** 批准出库：APPROVED -> BORROWED，贯通写 t_relic_loan(LENT) */
    @Transactional
    public BorrowApplication lend(Long applicationId, BorrowLendRequest req) {
        BorrowApplication app = requireApplication(applicationId);
        if (!BAPP_APPROVED.equals(app.getStatus())) {
            throw new IllegalArgumentException("借阅申请未处于已批准待出库状态，不能出库: "
                    + app.getApplicationNo() + "（" + app.getStatus() + "）");
        }
        // 提前收回补偿未闭环：等级调整后在途风险未处置，阻断出库
        Long openRecall = compensationMapper.selectCount(new LambdaQueryWrapper<com.evops.entity.BorrowCompensation>()
                .eq(com.evops.entity.BorrowCompensation::getApplicationId, applicationId)
                .eq(com.evops.entity.BorrowCompensation::getActionType, BCOMP_ACTION_RECALL)
                .eq(com.evops.entity.BorrowCompensation::getStatus, COMP_OPEN));
        if (openRecall != null && openRecall > 0) {
            throw new IllegalArgumentException("存在未闭环的提前收回补偿，文物禁止出库: " + app.getApplicationNo());
        }
        relicService.requireActiveRelic(app.getRelicId());
        // 出库前再校验：审批通过后文物被重新布展或重新进入修复流程的，仍不能出库
        Long onDisplayNow = hallRelicMapper.selectCount(new LambdaQueryWrapper<HallRelic>()
                .eq(HallRelic::getRelicId, app.getRelicId())
                .eq(HallRelic::getStatus, DISPLAY_ON));
        if (onDisplayNow != null && onDisplayNow > 0) {
            throw new IllegalArgumentException("文物已进入当前展陈计划（展出中），借阅禁止出库: "
                    + app.getApplicationNo());
        }
        Long openRepair = projectMapper.selectCount(new LambdaQueryWrapper<RepairProject>()
                .eq(RepairProject::getRelicId, app.getRelicId())
                .in(RepairProject::getStatus, PROJECT_PLANNED, PROJECT_REPAIRING, PROJECT_REPAIRED));
        if (openRepair != null && openRepair > 0) {
            throw new IllegalArgumentException("文物存在未验收闭环的修复项目，借阅禁止出库: "
                    + app.getApplicationNo());
        }

        LocalDateTime now = LocalDateTime.now();
        RelicLoan loan = new RelicLoan();
        loan.setLoanNo(app.getApplicationNo());
        loan.setRelicId(app.getRelicId());
        loan.setBorrower(app.getBorrower());
        loan.setPurpose("借阅场景：" + app.getBorrowScene()
                + (app.getPurpose() == null ? "" : "；" + app.getPurpose()));
        loan.setLentAt(now);
        loan.setExpectedReturnAt(app.getExpectedReturnAt());
        loan.setStatus(LOAN_LENT);
        relicLoanMapper.insert(loan);

        app.setStatus(BAPP_BORROWED);
        applicationMapper.updateById(app);

        ledgerService.append(app.getRelicId(), null, null,
                ACTION_BORROW_LEND, app.getApplicationNo(),
                "借阅批准出库，交接人：" + (req.getLentBy() == null ? "未登记" : req.getLentBy())
                        + "，借用方：" + app.getBorrower(), now);
        return app;
    }

    /** 归还：BORROWED -> RETURNED，闭环贯通借出单；审批中的延期单自动驳回 */
    @Transactional
    public BorrowApplication repay(Long applicationId, BorrowReturnRequest req) {
        BorrowApplication app = requireApplication(applicationId);
        if (!BAPP_BORROWED.equals(app.getStatus())) {
            throw new IllegalArgumentException("借阅申请不在借出状态，不能归还: "
                    + app.getApplicationNo() + "（" + app.getStatus() + "）");
        }
        LocalDateTime now = req.getReturnedAt() == null ? LocalDateTime.now() : req.getReturnedAt();

        List<BorrowExtension> pendingExts = extensionMapper.selectList(new LambdaQueryWrapper<BorrowExtension>()
                .eq(BorrowExtension::getApplicationId, applicationId)
                .eq(BorrowExtension::getStatus, EXT_PENDING));
        for (BorrowExtension ext : pendingExts) {
            ext.setStatus(EXT_REJECTED);
            ext.setHandledBy("SYSTEM");
            ext.setHandledAt(now);
            ext.setHandleOpinion("借阅已归还，延期重新审批自动终止");
            extensionMapper.updateById(ext);
            ledgerService.append(app.getRelicId(), null, null,
                    ACTION_BORROW_EXTEND_REJECT, ext.getExtensionNo(),
                    "借阅归还，审批中的延期单自动驳回，原应还时间口径以实际归还为准", now);
        }

        RelicLoan loan = relicLoanMapper.selectOne(new LambdaQueryWrapper<RelicLoan>()
                .eq(RelicLoan::getLoanNo, app.getApplicationNo())
                .eq(RelicLoan::getStatus, LOAN_LENT)
                .last("LIMIT 1"));
        if (loan != null) {
            loan.setStatus(LOAN_RETURNED);
            loan.setReturnedAt(now);
            loan.setReceiver(req.getReturnedBy());
            loan.setReturnCondition(req.getReturnCondition());
            relicLoanMapper.updateById(loan);
        }

        app.setStatus(BAPP_RETURNED);
        applicationMapper.updateById(app);

        // 提前收回（RECALL）补偿在文物实际归还后自动闭环
        List<com.evops.entity.BorrowCompensation> recalls = compensationMapper.selectList(
                new LambdaQueryWrapper<com.evops.entity.BorrowCompensation>()
                        .eq(com.evops.entity.BorrowCompensation::getApplicationId, applicationId)
                        .eq(com.evops.entity.BorrowCompensation::getStatus, COMP_OPEN));
        for (com.evops.entity.BorrowCompensation comp : recalls) {
            comp.setStatus(COMP_RESOLVED);
            comp.setResolvedBy(req.getReturnedBy());
            comp.setResolvedAt(now);
            comp.setResolveNote("文物已归还入库，提前收回/借阅补偿随归还闭环");
            compensationMapper.updateById(comp);
            ledgerService.append(app.getRelicId(), null, null,
                    ACTION_BORROW_COMP_RESOLVE, comp.getCompensationNo(),
                    "借阅补偿随归还自动闭环（" + comp.getActionType() + "）", now);
        }

        ledgerService.append(app.getRelicId(), null, null,
                ACTION_BORROW_RETURN, app.getApplicationNo(),
                "借阅归还入库，接收人：" + req.getReturnedBy()
                        + (req.getReturnCondition() == null ? "" : "，归还品相：" + req.getReturnCondition()),
                now);
        return app;
    }

    /** 申请人撤销：审批中可撤；已批准待出库且存在提前收回补偿时也允许撤（不出库即闭环风险） */
    @Transactional
    public BorrowApplication cancel(Long applicationId, BorrowCancelRequest req) {
        BorrowApplication app = requireApplication(applicationId);
        boolean recallOpen = compensationMapper.selectCount(
                new LambdaQueryWrapper<com.evops.entity.BorrowCompensation>()
                        .eq(com.evops.entity.BorrowCompensation::getApplicationId, applicationId)
                        .eq(com.evops.entity.BorrowCompensation::getStatus, COMP_OPEN)) > 0;
        if (!BAPP_PENDING.equals(app.getStatus())
                && !(BAPP_APPROVED.equals(app.getStatus()) && recallOpen)) {
            throw new IllegalArgumentException("当前状态不允许撤销借阅申请: "
                    + app.getApplicationNo() + "（" + app.getStatus() + "）");
        }
        LocalDateTime now = LocalDateTime.now();
        app.setStatus(BAPP_CANCELLED);
        app.setCancelledAt(now);
        applicationMapper.updateById(app);

        ledgerService.append(app.getRelicId(), null, null,
                ACTION_BORROW_CANCEL, app.getApplicationNo(),
                "借阅申请撤销，操作人：" + req.getOperator() + "，原因：" + req.getReason(), now);
        return app;
    }

    // ------------------------------------------------------------------
    // 归还延期（重新审批）
    // ------------------------------------------------------------------

    @Transactional
    public BorrowExtension applyExtension(Long applicationId, BorrowExtensionRequest req) {
        BorrowApplication app = requireApplication(applicationId);
        if (!BAPP_BORROWED.equals(app.getStatus())) {
            throw new IllegalArgumentException("只有借出在途的借阅才能申请归还延期: "
                    + app.getApplicationNo() + "（" + app.getStatus() + "）");
        }
        Long pending = extensionMapper.selectCount(new LambdaQueryWrapper<BorrowExtension>()
                .eq(BorrowExtension::getApplicationId, applicationId)
                .eq(BorrowExtension::getStatus, EXT_PENDING));
        if (pending != null && pending > 0) {
            throw new IllegalArgumentException("已存在重新审批中的延期申请，不能重复提交: "
                    + app.getApplicationNo());
        }
        if (!req.getRequestedReturnAt().isAfter(app.getExpectedReturnAt())) {
            throw new IllegalArgumentException("申请延期归还时间必须晚于当前应还时间: "
                    + app.getExpectedReturnAt());
        }

        Integer maxSeq = extensionMapper.selectList(new LambdaQueryWrapper<BorrowExtension>()
                        .eq(BorrowExtension::getApplicationId, applicationId)
                        .orderByDesc(BorrowExtension::getSeqNo)
                        .last("LIMIT 1"))
                .stream().findFirst().map(BorrowExtension::getSeqNo).orElse(0);
        LocalDateTime now = LocalDateTime.now();
        BorrowExtension ext = new BorrowExtension();
        ext.setExtensionNo(String.format("BE-%06d", extensionMapper.nextExtSeq()));
        ext.setApplicationId(applicationId);
        ext.setSeqNo(maxSeq + 1);
        ext.setOriginalExpectedReturnAt(app.getExpectedReturnAt());
        ext.setRequestedReturnAt(req.getRequestedReturnAt());
        ext.setReason(req.getReason());
        ext.setApprovalRoles(app.getApprovalRoles());
        ext.setDirectorSignRequired(app.getDirectorSignRequired());
        ext.setStatus(EXT_PENDING);
        ext.setSubmittedBy(req.getSubmittedBy());
        ext.setSubmittedAt(now);
        extensionMapper.insert(ext);

        ledgerService.append(app.getRelicId(), null, null,
                ACTION_BORROW_EXTEND_SUBMIT, ext.getExtensionNo(),
                "归还延期申请（第 " + ext.getSeqNo() + " 次），" + ext.getOriginalExpectedReturnAt()
                        + " 延至 " + ext.getRequestedReturnAt() + "，须重新审批"
                        + (ext.getDirectorSignRequired() == 1 ? "（含馆长追加签名）" : "")
                        + "，原因：" + req.getReason(), now);
        return ext;
    }

    /** 延期重审链签署 */
    @Transactional
    public BorrowApprovalRecord approveExtension(Long extensionId, BorrowApproveRequest req) {
        BorrowExtension ext = extensionMapper.selectById(extensionId);
        if (ext == null) {
            throw new IllegalArgumentException("延期申请不存在: id=" + extensionId);
        }
        if (!EXT_PENDING.equals(ext.getStatus())) {
            throw new IllegalArgumentException("延期申请不在重新审批中，不能签署: "
                    + ext.getExtensionNo() + "（" + ext.getStatus() + "）");
        }
        BorrowApplication app = requireApplication(ext.getApplicationId());
        if (!BAPP_BORROWED.equals(app.getStatus())) {
            throw new IllegalArgumentException("借阅已不在借出在途状态，延期重新审批终止: "
                    + app.getApplicationNo() + "（" + app.getStatus() + "）");
        }
        return signStep(app, ext.getId(), ext.getApprovalRoles(),
                ext.getDirectorSignRequired(), req);
    }

    private void applyApprovedExtension(BorrowApplication app, BorrowExtension ext,
                                        LocalDateTime now) {
        app.setExpectedReturnAt(ext.getRequestedReturnAt());
        applicationMapper.updateById(app);
        RelicLoan loan = relicLoanMapper.selectOne(new LambdaQueryWrapper<RelicLoan>()
                .eq(RelicLoan::getLoanNo, app.getApplicationNo())
                .eq(RelicLoan::getStatus, LOAN_LENT)
                .last("LIMIT 1"));
        if (loan != null) {
            loan.setExpectedReturnAt(ext.getRequestedReturnAt());
            relicLoanMapper.updateById(loan);
        }
    }

    // ------------------------------------------------------------------
    // 查询（馆区保险脱敏）
    // ------------------------------------------------------------------

    public BorrowApplication requireApplication(Long id) {
        BorrowApplication app = applicationMapper.selectById(id);
        if (app == null) {
            throw new IllegalArgumentException("借阅申请不存在: id=" + id);
        }
        return app;
    }

    /**
     * 申请详情：审批记录、借阅快照、延期单随返；保险金额按查看人馆区脱敏。
     * viewerCampus 为空或与文物馆区不一致时，不返回任何保险数字。
     */
    public Map<String, Object> detail(Long applicationId, String viewerCampus) {
        BorrowApplication app = requireApplication(applicationId);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("application", maskEntity(app, app.getCampusCode(), viewerCampus));
        detail.put("approvals", listChain(applicationId, ORIGINAL_CHAIN));

        BorrowSnapshot snapshot = snapshotMapper.selectOne(new LambdaQueryWrapper<BorrowSnapshot>()
                .eq(BorrowSnapshot::getApplicationId, applicationId));
        detail.put("snapshot", snapshot == null ? null
                : maskEntity(snapshot, snapshot.getCampusCode(), viewerCampus));

        List<BorrowExtension> exts = extensionMapper.selectList(new LambdaQueryWrapper<BorrowExtension>()
                .eq(BorrowExtension::getApplicationId, applicationId)
                .orderByAsc(BorrowExtension::getSeqNo));
        List<Map<String, Object>> extBundles = new ArrayList<>();
        for (BorrowExtension ext : exts) {
            Map<String, Object> bundle = new LinkedHashMap<>();
            bundle.put("extension", ext);
            bundle.put("approvals", listChain(applicationId, ext.getId()));
            extBundles.add(bundle);
        }
        detail.put("extensions", extBundles);
        return detail;
    }

    /** 列表：保险金额同样按查看人馆区脱敏 */
    public List<Map<String, Object>> list(Long relicId, String status, String viewerCampus) {
        LambdaQueryWrapper<BorrowApplication> wrapper = new LambdaQueryWrapper<BorrowApplication>()
                .orderByDesc(BorrowApplication::getId);
        if (relicId != null) {
            wrapper.eq(BorrowApplication::getRelicId, relicId);
        }
        if (status != null && !status.isEmpty()) {
            wrapper.eq(BorrowApplication::getStatus, status);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (BorrowApplication app : applicationMapper.selectList(wrapper)) {
            result.add(maskEntity(app, app.getCampusCode(), viewerCampus));
        }
        return result;
    }

    /**
     * 馆区保险脱敏：同馆区返回完整保险金额并置 insuranceViewable=true；
     * 非同馆区（含未声明馆区）保险金额置 null 且 insuranceViewable=false。
     */
    private Map<String, Object> maskEntity(Object entity, String ownerCampus, String viewerCampus) {
        Map<String, Object> map = objectMapper.convertValue(entity,
                new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
        boolean viewable = viewerCampus != null && viewerCampus.equals(ownerCampus);
        if (!viewable) {
            map.put("insuranceAmount", null);
        }
        map.put("insuranceViewable", viewable);
        return map;
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    private void generateSnapshot(BorrowApplication app, List<BorrowApprovalRecord> records,
                                  LocalDateTime now) {
        Relic relic = relicService.requireRelic(app.getRelicId());
        StringBuilder chain = new StringBuilder();
        String directorName = null;
        for (BorrowApprovalRecord r : records) {
            if (chain.length() > 0) {
                chain.append(" | ");
            }
            chain.append(r.getStepNo()).append(':').append(r.getApproverRole())
                    .append(':').append(r.getApproverName())
                    .append(r.getDirectorSign() != null && r.getDirectorSign() == 1 ? "(馆长签名)" : "")
                    .append(':').append(r.getDecision())
                    .append('@').append(r.getSignedAt());
            if (r.getDirectorSign() != null && r.getDirectorSign() == 1) {
                directorName = r.getApproverName();
            }
        }
        BorrowSnapshot snapshot = new BorrowSnapshot();
        snapshot.setApplicationId(app.getId());
        snapshot.setRelicId(relic.getId());
        snapshot.setRelicNo(relic.getRelicNo());
        snapshot.setRelicName(relic.getName());
        snapshot.setPreservationGrade(relic.getPreservationGrade());
        snapshot.setCampusCode(relic.getCampusCode());
        snapshot.setCategory(relic.getCategory());
        snapshot.setMaterial(relic.getMaterial());
        snapshot.setEra(relic.getEra());
        snapshot.setBorrower(app.getBorrower());
        snapshot.setBorrowScene(app.getBorrowScene());
        snapshot.setPurpose(app.getPurpose());
        snapshot.setInsuranceAmount(app.getInsuranceAmount());
        snapshot.setPlanLentAt(app.getPlanLentAt());
        snapshot.setExpectedReturnAt(app.getExpectedReturnAt());
        snapshot.setDurationDays(app.getDurationDays());
        snapshot.setApprovalRoles(app.getApprovalRoles());
        snapshot.setApprovalChain(chain.toString());
        snapshot.setDirectorSigned(app.getDirectorSignRequired());
        snapshot.setDirectorSignName(directorName);
        snapshot.setGeneratedAt(now);
        snapshotMapper.insert(snapshot);
    }

    private List<BorrowApprovalRecord> listChain(Long applicationId, long extensionId) {
        return approvalMapper.selectList(new LambdaQueryWrapper<BorrowApprovalRecord>()
                .eq(BorrowApprovalRecord::getApplicationId, applicationId)
                .eq(BorrowApprovalRecord::getExtensionId, extensionId)
                .orderByAsc(BorrowApprovalRecord::getStepNo));
    }

    private BorrowExtension requireExtension(Long id) {
        BorrowExtension ext = extensionMapper.selectById(id);
        if (ext == null) {
            throw new IllegalArgumentException("延期申请不存在: id=" + id);
        }
        return ext;
    }

    /** 有效审批链 = 配置角色序列，高风险再追加馆长签名步 */
    private List<String> effectiveRoles(String rolesCsv, int directorRequired) {
        List<String> roles = new ArrayList<>(parseRoles(rolesCsv));
        if (directorRequired == 1 && !roles.contains(BORROW_ROLE_DIRECTOR)) {
            roles.add(BORROW_ROLE_DIRECTOR);
        }
        return roles;
    }

    private List<String> parseRoles(String rolesCsv) {
        List<String> roles = new ArrayList<>();
        if (rolesCsv == null) {
            return roles;
        }
        for (String part : Arrays.asList(rolesCsv.split(","))) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                roles.add(trimmed);
            }
        }
        return roles;
    }
}
