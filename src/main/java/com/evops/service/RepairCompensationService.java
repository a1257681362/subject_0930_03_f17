package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.dto.BorrowCompensationResolveRequest;
import com.evops.dto.GradeCompensationResolveRequest;
import com.evops.entity.BorrowApplication;
import com.evops.entity.BorrowCompensation;
import com.evops.entity.BorrowSnapshot;
import com.evops.entity.Relic;
import com.evops.entity.RepairAcceptance;
import com.evops.entity.RepairGradeCompensation;
import com.evops.entity.RepairProject;
import com.evops.mapper.BorrowApplicationMapper;
import com.evops.mapper.BorrowCompensationMapper;
import com.evops.mapper.BorrowSnapshotMapper;
import com.evops.mapper.RepairGradeCompensationMapper;
import com.evops.mapper.RelicMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static com.evops.constant.MuseumConstants.*;

/**
 * 修复部分成功（验收附条件通过 CONDITIONAL）补偿联动（museum-10）：
 *
 * <p>验收附条件通过即开立一条保护等级补偿（一项目一条，固化等级前/后，
 * 调整立即落到文物档案）；若文物存在受影响借阅，则成对开立恰好一条借阅补偿，
 * 两条记录互持 id——在借/待出库为 RECALL（提前收回，阻断出库），
 * 最近一次已归还借阅为 SUPPLEMENT_CONFIRM（等级与保险差异补充确认）；
 * 审批中的借阅申请按新等级口径直接驳回（须重新申请）。</p>
 *
 * <p>闭环顺序：借阅补偿必须先闭环（文物已收回或差异已确认），
 * 保护等级补偿才允许确认；保护等级补偿确认时可再次更正最终等级。</p>
 */
@Service
public class RepairCompensationService {

    private static final List<String> GRADES = Arrays.asList(GRADE_ONE, GRADE_TWO, GRADE_THREE);

    private final RepairGradeCompensationMapper gradeCompMapper;
    private final BorrowCompensationMapper borrowCompMapper;
    private final BorrowApplicationMapper applicationMapper;
    private final BorrowSnapshotMapper snapshotMapper;
    private final RelicMapper relicMapper;
    private final LedgerService ledgerService;

    public RepairCompensationService(RepairGradeCompensationMapper gradeCompMapper,
                                     BorrowCompensationMapper borrowCompMapper,
                                     BorrowApplicationMapper applicationMapper,
                                     BorrowSnapshotMapper snapshotMapper,
                                     RelicMapper relicMapper,
                                     LedgerService ledgerService) {
        this.gradeCompMapper = gradeCompMapper;
        this.borrowCompMapper = borrowCompMapper;
        this.applicationMapper = applicationMapper;
        this.snapshotMapper = snapshotMapper;
        this.relicMapper = relicMapper;
        this.ledgerService = ledgerService;
    }

    /**
     * 验收附条件通过后的补偿开立（与验收同一事务）。
     * 已由 RepairService 校验 result=CONDITIONAL 且 gradeAfter 合法。
     */
    @Transactional
    public RepairGradeCompensation openOnConditionalAccept(RepairProject project,
                                                           RepairAcceptance acceptance,
                                                           String gradeBefore,
                                                           String gradeAfter,
                                                           String opinion) {
        LocalDateTime now = acceptance.getAcceptedAt();
        RepairGradeCompensation gradeComp = new RepairGradeCompensation();
        gradeComp.setCompensationNo(String.format("GC-%06d", gradeCompMapper.nextCompSeq()));
        gradeComp.setProjectId(project.getId());
        gradeComp.setRelicId(project.getRelicId());
        gradeComp.setAcceptanceId(acceptance.getId());
        gradeComp.setGradeBefore(gradeBefore);
        gradeComp.setGradeAfter(gradeAfter);
        gradeComp.setReason(opinion == null || opinion.trim().isEmpty()
                ? "验收附条件通过（部分成功），保护等级调整待补偿确认" : opinion);
        gradeComp.setStatus(COMP_OPEN);
        gradeComp.setOpenedBy(acceptance.getAcceptedBy());
        gradeComp.setOpenedAt(now);
        gradeCompMapper.insert(gradeComp);

        // 等级调整立即落文物档案；补偿确认时还可再更正最终等级
        Relic relic = relicMapper.selectById(project.getRelicId());
        relic.setPreservationGrade(gradeAfter);
        relicMapper.updateById(relic);

        BorrowCompensation paired = pairBorrowCompensation(gradeComp, now);
        if (paired != null) {
            gradeComp.setBorrowCompensationId(paired.getId());
            gradeCompMapper.updateById(gradeComp);
        }

        ledgerService.append(project.getRelicId(), project.getId(), null,
                ACTION_GRADE_COMP_OPEN, gradeComp.getCompensationNo(),
                "验收附条件通过开立保护等级补偿：" + gradeBefore + " -> " + gradeAfter
                        + (paired == null ? "，无在途借阅影响"
                        : "，已成对开立借阅补偿 " + paired.getCompensationNo()
                                + "（" + paired.getActionType() + "）"), now);
        return gradeComp;
    }

    /**
     * 按受影响借阅成对开立借阅补偿（至多一条，保证与保护等级补偿一一对应）：
     * 1) 在借/待出库（BORROWED/APPROVED）→ RECALL，未闭环不得出库、须收回后闭环；
     * 2) 无在途但有最近一次已归还借阅 → SUPPLEMENT_CONFIRM 补充确认；
     * 3) 审批中的申请按新等级口径驳回，由申请人重新提交（不形成补偿单）。
     */
    private BorrowCompensation pairBorrowCompensation(RepairGradeCompensation gradeComp,
                                                      LocalDateTime now) {
        Long relicId = gradeComp.getRelicId();

        List<BorrowApplication> inFlight = applicationMapper.selectList(new LambdaQueryWrapper<BorrowApplication>()
                .eq(BorrowApplication::getRelicId, relicId)
                .in(BorrowApplication::getStatus, BAPP_APPROVED, BAPP_BORROWED)
                .orderByDesc(BorrowApplication::getId));
        if (!inFlight.isEmpty()) {
            BorrowApplication app = inFlight.get(0);
            BorrowCompensation comp = baseComp(gradeComp, app, BCOMP_ACTION_RECALL, now);
            borrowCompMapper.insert(comp);
            ledgerService.append(relicId, null, null,
                    ACTION_BORROW_COMP_OPEN, comp.getCompensationNo(),
                    "保护等级调整（" + gradeComp.getGradeBefore() + " -> " + gradeComp.getGradeAfter()
                            + "）对在途借阅 " + app.getApplicationNo() + " 开立提前收回补偿，"
                            + (BAPP_APPROVED.equals(app.getStatus()) ? "文物禁止出库" : "须提前收回"),
                    now);
            return comp;
        }

        // 审批中的申请：旧等级策略快照失效，驳回后按新等级重新申请
        List<BorrowApplication> pending = applicationMapper.selectList(new LambdaQueryWrapper<BorrowApplication>()
                .eq(BorrowApplication::getRelicId, relicId)
                .eq(BorrowApplication::getStatus, BAPP_PENDING)
                .orderByDesc(BorrowApplication::getId));
        for (BorrowApplication app : pending) {
            app.setStatus(BAPP_REJECTED);
            app.setRejectReason("修复验收附条件通过，文物保护等级由 " + gradeComp.getGradeBefore()
                    + " 调整为 " + gradeComp.getGradeAfter() + "，原申请策略快照失效，须按新等级重新申请");
            applicationMapper.updateById(app);
            ledgerService.append(relicId, null, null,
                    ACTION_BORROW_REJECT, app.getApplicationNo(),
                    "保护等级调整，审批中的借阅申请驳回，须按新等级重新申请", now);
        }

        BorrowApplication latestReturned = applicationMapper.selectOne(new LambdaQueryWrapper<BorrowApplication>()
                .eq(BorrowApplication::getRelicId, relicId)
                .eq(BorrowApplication::getStatus, BAPP_RETURNED)
                .orderByDesc(BorrowApplication::getId)
                .last("LIMIT 1"));
        if (latestReturned != null) {
            BorrowCompensation comp = baseComp(gradeComp, latestReturned,
                    BCOMP_ACTION_SUPPLEMENT, now);
            borrowCompMapper.insert(comp);
            ledgerService.append(relicId, null, null,
                    ACTION_BORROW_COMP_OPEN, comp.getCompensationNo(),
                    "保护等级调整（" + gradeComp.getGradeBefore() + " -> " + gradeComp.getGradeAfter()
                            + "）对最近归还借阅 " + latestReturned.getApplicationNo()
                            + " 开立等级与保险差异补充确认补偿", now);
            return comp;
        }
        return null;
    }

    private BorrowCompensation baseComp(RepairGradeCompensation gradeComp, BorrowApplication app,
                                        String actionType, LocalDateTime now) {
        BorrowCompensation comp = new BorrowCompensation();
        comp.setCompensationNo(String.format("BC-%06d", borrowCompMapper.nextCompSeq()));
        comp.setApplicationId(app.getId());
        comp.setRelicId(app.getRelicId());
        comp.setGradeCompensationId(gradeComp.getId());
        comp.setGradeBefore(gradeComp.getGradeBefore());
        comp.setGradeAfter(gradeComp.getGradeAfter());
        comp.setActionType(actionType);
        comp.setInsuranceBefore(app.getInsuranceAmount());
        comp.setStatus(COMP_OPEN);
        comp.setOpenedAt(now);
        comp.setOpenedBy(gradeComp.getOpenedBy());
        BorrowSnapshot snapshot = snapshotMapper.selectOne(new LambdaQueryWrapper<BorrowSnapshot>()
                .eq(BorrowSnapshot::getApplicationId, app.getId()));
        if (snapshot != null) {
            comp.setSnapshotId(snapshot.getId());
        }
        return comp;
    }

    /**
     * 借阅补偿闭环：
     * RECALL 要求借阅已不在途（已归还/撤销/驳回）；SUPPLEMENT_CONFIRM 直接补充确认。
     */
    @Transactional
    public BorrowCompensation resolveBorrowCompensation(Long compId,
                                                        BorrowCompensationResolveRequest req) {
        BorrowCompensation comp = requireBorrowCompensation(compId);
        if (COMP_RESOLVED.equals(comp.getStatus())) {
            throw new IllegalArgumentException("借阅补偿已闭环，不能重复确认: " + comp.getCompensationNo());
        }
        if (BCOMP_ACTION_RECALL.equals(comp.getActionType())) {
            BorrowApplication app = applicationMapper.selectById(comp.getApplicationId());
            if (app != null && (BAPP_APPROVED.equals(app.getStatus())
                    || BAPP_BORROWED.equals(app.getStatus()) || BAPP_PENDING.equals(app.getStatus()))) {
                throw new IllegalArgumentException("提前收回补偿要求文物已收回（借阅归还或撤销）后才能闭环："
                        + "当前借阅状态 " + app.getStatus());
            }
        }
        LocalDateTime now = LocalDateTime.now();
        comp.setStatus(COMP_RESOLVED);
        comp.setResolvedBy(req.getResolvedBy());
        comp.setResolvedAt(now);
        comp.setResolveNote(req.getNote());
        if (req.getInsuranceAfter() != null) {
            comp.setInsuranceAfter(req.getInsuranceAfter());
        }
        borrowCompMapper.updateById(comp);

        ledgerService.append(comp.getRelicId(), null, null,
                ACTION_BORROW_COMP_RESOLVE, comp.getCompensationNo(),
                "借阅补偿（" + comp.getActionType() + "）闭环，确认人：" + req.getResolvedBy()
                        + "，说明：" + req.getNote()
                        + (req.getInsuranceAfter() == null ? ""
                        : "，确认后保险金额：" + req.getInsuranceAfter()), now);
        return comp;
    }

    /**
     * 保护等级补偿闭环确认：成对的借阅补偿必须先闭环；
     * 可给出最终等级（再次更正文物档案），缺省按验收时调整后等级确认。
     */
    @Transactional
    public RepairGradeCompensation resolveGradeCompensation(Long compId,
                                                            GradeCompensationResolveRequest req) {
        RepairGradeCompensation comp = requireGradeCompensation(compId);
        if (COMP_RESOLVED.equals(comp.getStatus())) {
            throw new IllegalArgumentException("保护等级补偿已闭环，不能重复确认: " + comp.getCompensationNo());
        }
        if (comp.getBorrowCompensationId() != null) {
            BorrowCompensation paired = borrowCompMapper.selectById(comp.getBorrowCompensationId());
            if (paired != null && !COMP_RESOLVED.equals(paired.getStatus())) {
                throw new IllegalArgumentException("成对的借阅补偿 " + paired.getCompensationNo()
                        + " 尚未闭环，保护等级补偿不能先确认");
            }
        }
        String finalGrade = (req.getResolvedGrade() == null || req.getResolvedGrade().trim().isEmpty())
                ? comp.getGradeAfter() : req.getResolvedGrade().trim();
        if (!GRADES.contains(finalGrade)) {
            throw new IllegalArgumentException("最终保护等级只能为 ONE/TWO/THREE");
        }
        LocalDateTime now = LocalDateTime.now();
        comp.setStatus(COMP_RESOLVED);
        comp.setResolvedGrade(finalGrade);
        comp.setResolvedBy(req.getResolvedBy());
        comp.setResolvedAt(now);
        comp.setResolveNote(req.getNote());
        gradeCompMapper.updateById(comp);

        Relic relic = relicMapper.selectById(comp.getRelicId());
        relic.setPreservationGrade(finalGrade);
        relicMapper.updateById(relic);

        ledgerService.append(comp.getRelicId(), comp.getProjectId(), null,
                ACTION_GRADE_COMP_RESOLVE, comp.getCompensationNo(),
                "保护等级补偿闭环确认，最终等级：" + finalGrade + "，确认人：" + req.getResolvedBy()
                        + "，说明：" + req.getNote(), now);
        return comp;
    }

    public RepairGradeCompensation requireGradeCompensation(Long id) {
        RepairGradeCompensation comp = gradeCompMapper.selectById(id);
        if (comp == null) {
            throw new IllegalArgumentException("保护等级补偿不存在: id=" + id);
        }
        return comp;
    }

    public BorrowCompensation requireBorrowCompensation(Long id) {
        BorrowCompensation comp = borrowCompMapper.selectById(id);
        if (comp == null) {
            throw new IllegalArgumentException("借阅补偿不存在: id=" + id);
        }
        return comp;
    }

    public List<RepairGradeCompensation> listGradeComps(Long relicId, String status) {
        LambdaQueryWrapper<RepairGradeCompensation> wrapper =
                new LambdaQueryWrapper<RepairGradeCompensation>()
                        .orderByDesc(RepairGradeCompensation::getId);
        if (relicId != null) {
            wrapper.eq(RepairGradeCompensation::getRelicId, relicId);
        }
        if (status != null && !status.isEmpty()) {
            wrapper.eq(RepairGradeCompensation::getStatus, status);
        }
        return gradeCompMapper.selectList(wrapper);
    }

    public List<BorrowCompensation> listBorrowComps(Long relicId, String status,
                                                    String viewerCampus) {
        LambdaQueryWrapper<BorrowCompensation> wrapper =
                new LambdaQueryWrapper<BorrowCompensation>()
                        .orderByDesc(BorrowCompensation::getId);
        if (relicId != null) {
            wrapper.eq(BorrowCompensation::getRelicId, relicId);
        }
        if (status != null && !status.isEmpty()) {
            wrapper.eq(BorrowCompensation::getStatus, status);
        }
        List<BorrowCompensation> comps = borrowCompMapper.selectList(wrapper);
        for (BorrowCompensation comp : comps) {
            maskByCampus(comp, viewerCampus);
        }
        return comps;
    }

    /** 单条借阅补偿查询，同样按查看人馆区对保险金额脱敏 */
    public BorrowCompensation getBorrowCompensationMasked(Long id, String viewerCampus) {
        BorrowCompensation comp = requireBorrowCompensation(id);
        maskByCampus(comp, viewerCampus);
        return comp;
    }

    /** 保险金额按馆区隔离：非同馆区（含未声明馆区）不返回任何保险数字 */
    private void maskByCampus(BorrowCompensation comp, String viewerCampus) {
        boolean sameCampus = viewerCampus != null;
        if (sameCampus) {
            BorrowApplication app = applicationMapper.selectById(comp.getApplicationId());
            sameCampus = app != null && viewerCampus.equals(app.getCampusCode());
        }
        if (!sameCampus) {
            comp.setInsuranceBefore(null);
            comp.setInsuranceAfter(null);
        }
    }
}
