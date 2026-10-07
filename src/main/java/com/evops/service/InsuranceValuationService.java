package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.dto.ValuationApproveRequest;
import com.evops.dto.ValuationRegisterRequest;
import com.evops.dto.ValuationReminderAckRequest;
import com.evops.dto.ValuationSubmitRequest;
import com.evops.entity.InsuranceEndorsement;
import com.evops.entity.InsuranceValuationReminder;
import com.evops.entity.InsuranceValuationSnapshot;
import com.evops.entity.InsuranceValuationVersion;
import com.evops.entity.LoanAgreement;
import com.evops.entity.LoanAgreementVersion;
import com.evops.entity.Relic;
import com.evops.mapper.InsuranceEndorsementMapper;
import com.evops.mapper.InsuranceValuationReminderMapper;
import com.evops.mapper.InsuranceValuationSnapshotMapper;
import com.evops.mapper.InsuranceValuationVersionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.evops.constant.MuseumConstants.*;

/**
 * 文物保险估值管理（museum-15）：
 * <ul>
 *   <li>估值按文物版本化：记录来源（购入/评估/拍卖/捐赠）、评估机构、币种与
 *       有效期；任何金额修订都另出新版本，旧版本置 SUPERSEDED 永不改写。</li>
 *   <li>金额修订双人审批：初审（FIRST）与复核（SECOND）必须两名不同实签人，
 *       且两名审批人均不得是提交人本人；两签齐备新版本才 ACTIVE，生效同事务
 *       出具不可变保险批单（INITIAL/ADJUST）并回填 endorsement_id。</li>
 *   <li>借展签约时固化不可变快照；借展期间（押运/在展/归还）一律读快照，
 *       当前估值随后修订或过期都不影响在借口径。</li>
 *   <li>估值过期由定时扫描生成提醒，提醒只提示、不冻结归还。</li>
 *   <li>读取视图按角色收口：财务只见金额与凭证；公众/普通馆员访问敏感估值
 *             一律拒绝并写拒绝台账。</li>
 *   <li>所有变更动作首行先过 {@link PermissionService#requireValuationChangeOpen()}
 *       入口闸门：博物馆文物修复用户缺权时在服务层拒绝，换调用地址同样拒绝。</li>
 * </ul>
 */
@Service
public class InsuranceValuationService {

    private static final List<String> SOURCE_TYPES = Arrays.asList(
            "PURCHASE", "APPRAISAL", "AUCTION", "DONATION", "OTHER");
    private static final List<String> CURRENCIES = Arrays.asList(
            "CNY", "USD", "EUR", "JPY", "GBP", "HKD");

    private final InsuranceValuationVersionMapper versionMapper;
    private final InsuranceEndorsementMapper endorsementMapper;
    private final InsuranceValuationSnapshotMapper snapshotMapper;
    private final InsuranceValuationReminderMapper reminderMapper;
    private final RelicService relicService;
    private final LedgerService ledgerService;
    private final PermissionService permissionService;
    private final ValuationAccessAuditService accessAuditService;

    public InsuranceValuationService(InsuranceValuationVersionMapper versionMapper,
                                     InsuranceEndorsementMapper endorsementMapper,
                                     InsuranceValuationSnapshotMapper snapshotMapper,
                                     InsuranceValuationReminderMapper reminderMapper,
                                     RelicService relicService,
                                     LedgerService ledgerService,
                                     PermissionService permissionService,
                                     ValuationAccessAuditService accessAuditService) {
        this.versionMapper = versionMapper;
        this.endorsementMapper = endorsementMapper;
        this.snapshotMapper = snapshotMapper;
        this.reminderMapper = reminderMapper;
        this.relicService = relicService;
        this.ledgerService = ledgerService;
        this.permissionService = permissionService;
        this.accessAuditService = accessAuditService;
    }

    // ---- 登记 / 提交 ----

    /**
     * 登记一个估值版本（默认 DRAFT）。文物已有 ACTIVE 估值时本次登记即金额修订，
     * 必须填写 changeReason；submitNow=true 时登记后直接进入双人审批。
     */
    @Transactional
    public InsuranceValuationVersion register(ValuationRegisterRequest req) {
        permissionService.requireValuationChangeOpen();
        Relic relic = relicService.requireActiveRelic(req.getRelicId());
        validateFields(req.getSourceType(), req.getCurrency(),
                req.getEffectiveFrom(), req.getEffectiveTo());

        InsuranceValuationVersion active = activeVersionOf(relic.getId());
        boolean revision = active != null;
        if (revision && (req.getChangeReason() == null || req.getChangeReason().trim().isEmpty())) {
            throw new IllegalArgumentException("文物已有生效估值（" + active.getValuationNo()
                    + " V" + active.getVersionNo() + "），金额修订必须填写修订原因");
        }

        LocalDateTime now = LocalDateTime.now();
        InsuranceValuationVersion v = new InsuranceValuationVersion();
        v.setValuationNo(nextValuationNo());
        v.setRelicId(relic.getId());
        v.setVersionNo(nextVersionNo(relic.getId()));
        v.setSourceType(req.getSourceType());
        v.setSourceDesc(req.getSourceDesc());
        v.setAppraisalOrg(req.getAppraisalOrg());
        v.setAmount(req.getAmount());
        v.setCurrency(req.getCurrency());
        v.setEffectiveFrom(req.getEffectiveFrom());
        v.setEffectiveTo(req.getEffectiveTo());
        v.setVoucherNo(req.getVoucherNo());
        v.setVoucherName(req.getVoucherName());
        v.setChangeReason(req.getChangeReason());
        v.setSubmittedBy(req.getSubmittedBy());
        boolean submitNow = Boolean.TRUE.equals(req.getSubmitNow());
        v.setStatus(submitNow ? VAL_PENDING_APPROVAL : VAL_DRAFT);
        if (submitNow) {
            v.setSubmittedAt(now);
        }
        versionMapper.insert(v);

        ledgerService.append(relic.getId(), null, null,
                ACTION_VAL_DRAFT, v.getValuationNo(),
                "登记保险估值 V" + v.getVersionNo() + "（" + sourceName(req.getSourceType())
                        + "，评估机构：" + req.getAppraisalOrg()
                        + "，金额 " + req.getAmount() + " " + req.getCurrency()
                        + "，有效期至 " + req.getEffectiveTo() + "）"
                        + (revision ? "，金额修订待双人审批" : "，首次估值"), now);
        if (submitNow) {
            ledgerService.append(relic.getId(), null, null,
                    ACTION_VAL_SUBMIT, v.getValuationNo(),
                    "估值 V" + v.getVersionNo() + " 提交双人审批（初审+复核，两人不得为同一人）",
                    now);
        }
        return v;
    }

    /** 草稿提交双人审批：仅 DRAFT 可提交 */
    @Transactional
    public InsuranceValuationVersion submit(Long versionId, ValuationSubmitRequest req) {
        permissionService.requireValuationChangeOpen();
        InsuranceValuationVersion v = requireVersion(versionId);
        if (!VAL_DRAFT.equals(v.getStatus())) {
            throw new IllegalArgumentException("估值版本不是草稿，不能提交审批（当前状态 "
                    + v.getStatus() + "）");
        }
        if (v.getChangeReason() == null || v.getChangeReason().trim().isEmpty()) {
            if (req.getChangeReason() == null || req.getChangeReason().trim().isEmpty()) {
                InsuranceValuationVersion active = activeVersionOf(v.getRelicId());
                if (active != null) {
                    throw new IllegalArgumentException("金额修订提交时必须填写修订原因");
                }
            } else {
                v.setChangeReason(req.getChangeReason().trim());
            }
        }
        LocalDateTime now = LocalDateTime.now();
        v.setStatus(VAL_PENDING_APPROVAL);
        if (req.getSubmittedBy() != null && !req.getSubmittedBy().isEmpty()) {
            v.setSubmittedBy(req.getSubmittedBy());
        }
        v.setSubmittedAt(now);
        versionMapper.updateById(v);
        ledgerService.append(v.getRelicId(), null, null,
                ACTION_VAL_SUBMIT, v.getValuationNo(),
                "估值 V" + v.getVersionNo() + " 由 " + v.getSubmittedBy()
                        + " 提交双人审批（初审+复核，两人不得为同一人）", now);
        return v;
    }

    /**
     * 双人审批签署。初审/复核都通过后新版本生效：旧 ACTIVE 置 SUPERSEDED、
     * 新版本置 ACTIVE 并出具保险批单回填；任一签驳回置 REJECTED 留痕。
     */
    @Transactional
    public InsuranceValuationVersion approve(Long versionId, ValuationApproveRequest req) {
        permissionService.requireValuationChangeOpen();
        InsuranceValuationVersion v = requireVersion(versionId);
        if (!VAL_PENDING_APPROVAL.equals(v.getStatus())) {
            throw new IllegalArgumentException("估值版本不在审批中，不能签署（当前状态 "
                    + v.getStatus() + "）");
        }
        boolean approve = BORROW_DECISION_APPROVE.equals(req.getDecision());
        boolean reject = BORROW_DECISION_REJECT.equals(req.getDecision());
        if (!approve && !reject) {
            throw new IllegalArgumentException("审批决定只能为 APPROVE/REJECT");
        }
        if (reject && (req.getRejectReason() == null || req.getRejectReason().trim().isEmpty())) {
            throw new IllegalArgumentException("驳回必须填写驳回原因");
        }
        // 经办与审批分离：两名审批人均不得是提交人本人
        if (req.getApprover().equals(v.getSubmittedBy())) {
            throw new IllegalArgumentException("金额修订双人审批人不得是提交人本人：提交人为 "
                    + v.getSubmittedBy());
        }
        LocalDateTime now = LocalDateTime.now();
        String step = req.getStep();

        if (reject) {
            v.setStatus(VAL_REJECTED);
            v.setRejectedBy(req.getApprover());
            v.setRejectedAt(now);
            v.setRejectReason("【" + stepName(step) + "驳回】" + req.getRejectReason().trim());
            versionMapper.updateById(v);
            ledgerService.append(v.getRelicId(), null, null,
                    ACTION_VAL_REJECT, v.getValuationNo(),
                    stepName(step) + "人 " + req.getApprover() + " 驳回估值 V"
                            + v.getVersionNo() + "，原因：" + req.getRejectReason().trim()
                            + "（版本留痕不删档，修订须重新登记新版本）", now);
            return v;
        }

        if (VAL_APPROVAL_FIRST.equals(step)) {
            if (v.getFirstApprover() != null) {
                throw new IllegalArgumentException("初审已完成，不能重复初审（初审人 "
                        + v.getFirstApprover() + "）");
            }
            v.setFirstApprover(req.getApprover());
            v.setFirstApprovedAt(now);
            v.setFirstOpinion(req.getOpinion());
            versionMapper.updateById(v);
            ledgerService.append(v.getRelicId(), null, null,
                    ACTION_VAL_APPROVE, v.getValuationNo(),
                    "估值 V" + v.getVersionNo() + " 初审通过：" + req.getApprover()
                            + (req.getOpinion() == null ? "" : "，意见：" + req.getOpinion())
                            + "；待复核", now);
            return v;
        }

        if (!VAL_APPROVAL_SECOND.equals(step)) {
            throw new IllegalArgumentException("审批位次只能为 FIRST（初审）/SECOND（复核）");
        }
        if (v.getFirstApprover() == null) {
            throw new IllegalArgumentException("初审尚未完成，不能进行复核");
        }
        if (req.getApprover().equals(v.getFirstApprover())) {
            throw new IllegalArgumentException("金额修订必须双人审批：复核人不得与初审人为同一人（"
                    + v.getFirstApprover() + "）");
        }
        v.setSecondApprover(req.getApprover());
        v.setSecondApprovedAt(now);
        v.setSecondOpinion(req.getOpinion());
        activate(v, req.getApprover(), now);
        return v;
    }

    /** 新版本生效：旧 ACTIVE 置 SUPERSEDED，新版本 ACTIVE 并出具保险批单回填 */
    private void activate(InsuranceValuationVersion v, String secondApprover, LocalDateTime now) {
        InsuranceValuationVersion previous = activeVersionOf(v.getRelicId());

        InsuranceEndorsement endorsement = new InsuranceEndorsement();
        endorsement.setEndorsementNo(nextEndorsementNo());
        endorsement.setRelicId(v.getRelicId());
        endorsement.setValuationVersionId(v.getId());
        endorsement.setEndorsementType(previous == null ? "INITIAL" : "ADJUST");
        if (previous != null) {
            endorsement.setPreviousVersionId(previous.getId());
            endorsement.setPreviousAmount(previous.getAmount());
            endorsement.setPreviousCurrency(previous.getCurrency());
            previous.setStatus(VAL_SUPERSEDED);
            versionMapper.updateById(previous);
        }
        endorsement.setNewAmount(v.getAmount());
        endorsement.setNewCurrency(v.getCurrency());
        endorsement.setChangeReason(v.getChangeReason());
        endorsement.setIssuedBy(secondApprover);
        endorsement.setIssuedAt(now);
        endorsement.setStatus(ENDORSEMENT_ISSUED);
        endorsementMapper.insert(endorsement);

        v.setStatus(VAL_ACTIVE);
        v.setActivatedAt(now);
        v.setEndorsementId(endorsement.getId());
        versionMapper.updateById(v);

        ledgerService.append(v.getRelicId(), null, null,
                ACTION_VAL_APPROVE, v.getValuationNo(),
                "估值 V" + v.getVersionNo() + " 复核通过：" + secondApprover
                        + "（初审 " + v.getFirstApprover() + "），双人审批齐备", now);
        ledgerService.append(v.getRelicId(), null, null,
                ACTION_VAL_ACTIVATE, v.getValuationNo(),
                "估值新版本 V" + v.getVersionNo() + " 生效，金额 " + v.getAmount() + " "
                        + v.getCurrency()
                        + (previous == null ? "（首次生效）"
                        : "，旧版本 V" + previous.getVersionNo() + " 置 SUPERSEDED 不改写"), now);
        ledgerService.append(v.getRelicId(), null, null,
                ACTION_VAL_ENDORSEMENT, endorsement.getEndorsementNo(),
                "出具保险批单 " + endorsement.getEndorsementNo() + "（"
                        + ("INITIAL".equals(endorsement.getEndorsementType()) ? "首次核保定价"
                        : "金额变更批单") + "）并回填估值 V" + v.getVersionNo()
                        + (previous == null ? ""
                        : "：" + previous.getAmount() + " " + previous.getCurrency() + " -> "
                                + v.getAmount() + " " + v.getCurrency()), now);
    }

    // ---- 借展签约快照 ----

    /**
     * 借展协议签署后固化签约时估值快照（由借展签署流程在同事务内调用）。
     * 幂等：同一协议签署版本只出一份快照；不修改任何估值版本，故不受变更闸门影响。
     */
    @Transactional
    public InsuranceValuationSnapshot snapshotOnAgreementSigned(LoanAgreement agreement,
                                                                LoanAgreementVersion signedVersion,
                                                                String signedBy,
                                                                LocalDateTime now) {
        InsuranceValuationSnapshot existing = snapshotMapper.selectOne(
                new LambdaQueryWrapper<InsuranceValuationSnapshot>()
                        .eq(InsuranceValuationSnapshot::getAgreementVersionId, signedVersion.getId())
                        .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        InsuranceValuationVersion active = activeVersionOf(agreement.getRelicId());

        InsuranceValuationSnapshot s = new InsuranceValuationSnapshot();
        s.setSnapshotNo(nextSnapshotNo());
        s.setRelicId(agreement.getRelicId());
        s.setAgreementId(agreement.getId());
        s.setAgreementNo(agreement.getAgreementNo());
        s.setAgreementVersionId(signedVersion.getId());
        s.setBorrower(signedVersion.getBorrower());
        s.setSignedBy(signedBy);
        s.setGeneratedAt(now);
        String note;
        if (active == null) {
            note = "签约时文物无生效（ACTIVE）估值，快照金额留空；借展期间沿用本快照口径";
        } else {
            s.setValuationVersionId(active.getId());
            s.setVersionNo(active.getVersionNo());
            s.setSourceType(active.getSourceType());
            s.setSourceDesc(active.getSourceDesc());
            s.setAppraisalOrg(active.getAppraisalOrg());
            s.setAmount(active.getAmount());
            s.setCurrency(active.getCurrency());
            s.setEffectiveFrom(active.getEffectiveFrom());
            s.setEffectiveTo(active.getEffectiveTo());
            s.setVoucherNo(active.getVoucherNo());
            boolean expiredAtSign = active.getEffectiveTo() != null && active.getEffectiveTo().isBefore(now);
            note = "借展签约时按生效估值 " + active.getValuationNo() + " V" + active.getVersionNo()
                    + " 固化，借展期间一律沿用本快照，不受后续估值修订/批单/到期影响"
                    + (expiredAtSign ? "；注意：签约时该估值有效期已过，仍按签约快照执行并提醒续保" : "");
        }
        s.setNote(note);
        snapshotMapper.insert(s);

        ledgerService.append(agreement.getRelicId(), null, null,
                ACTION_VAL_SNAPSHOT, s.getSnapshotNo(),
                "借展协议 " + agreement.getAgreementNo() + " V" + signedVersion.getVersionNo()
                        + " 签署，固化签约时估值快照（不可变）：" + note, now);
        return s;
    }

    /**
     * 借展视角：借展期间一律返回签约时快照（即使当前估值已有新版本/已过期）。
     * 角色收口：财务只见金额与凭证；公众/普通馆员拒绝并留痕。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> loanView(Long agreementId, String operatorName, String operatorRole) {
        InsuranceValuationSnapshot snapshot = latestSnapshotOfAgreement(agreementId);
        if (snapshot == null) {
            throw new IllegalArgumentException("借展协议尚未签署或无签约估值快照: id=" + agreementId);
        }
        return toRoleView(snapshot, operatorName, operatorRole, true);
    }

    // ---- 查询（角色脱敏） ----

    /** 当前生效估值视图（含批单信息），按角色返回不同字段面 */
    @Transactional(readOnly = true)
    public Map<String, Object> currentView(Long relicId, String operatorName, String operatorRole) {
        relicService.requireActiveRelic(relicId);
        // 无估值也要先收口入口：公众/普通馆员不得借空结果探测估值是否存在
        denyIfSensitiveRole(operatorRole, operatorName, relicId, "RELIC#" + relicId);
        InsuranceValuationVersion active = activeVersionOf(relicId);
        if (active == null) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("relicId", relicId);
            empty.put("status", "NONE");
            return empty;
        }
        Map<String, Object> view = toRoleView(active, operatorName, operatorRole, false);
        if (ROLE_VALUATION_OFFICER.equals(operatorRole) || "CURATOR".equals(operatorRole)
                || "DIRECTOR".equals(operatorRole)) {
            InsuranceEndorsement endorsement = active.getEndorsementId() == null ? null
                    : endorsementMapper.selectById(active.getEndorsementId());
            if (endorsement != null) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("endorsementNo", endorsement.getEndorsementNo());
                e.put("endorsementType", endorsement.getEndorsementType());
                e.put("previousAmount", endorsement.getPreviousAmount());
                e.put("previousCurrency", endorsement.getPreviousCurrency());
                e.put("newAmount", endorsement.getNewAmount());
                e.put("newCurrency", endorsement.getNewCurrency());
                e.put("issuedBy", endorsement.getIssuedBy());
                e.put("issuedAt", endorsement.getIssuedAt());
                view.put("endorsement", e);
            }
        }
        return view;
    }

    /**
     * 估值版本全量履历：按版本号升序。角色收口——财务仅金额/凭证投影，
     * 公众/普通馆员入口拒绝并留痕。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> versions(Long relicId, String operatorName, String operatorRole) {
        relicService.requireActiveRelic(relicId);
        denyIfSensitiveRole(operatorRole, operatorName, relicId, "RELIC#" + relicId);
        List<InsuranceValuationVersion> versions = versionMapper.selectList(
                new LambdaQueryWrapper<InsuranceValuationVersion>()
                        .eq(InsuranceValuationVersion::getRelicId, relicId)
                        .orderByAsc(InsuranceValuationVersion::getVersionNo));
        List<Map<String, Object>> result = new ArrayList<>();
        for (InsuranceValuationVersion v : versions) {
            Map<String, Object> row = new LinkedHashMap<>();
            if (ROLE_FINANCE.equals(operatorRole)) {
                row.put("id", v.getId());
                row.put("valuationNo", v.getValuationNo());
                row.put("versionNo", v.getVersionNo());
                row.put("status", v.getStatus());
                row.put("amount", v.getAmount());
                row.put("currency", v.getCurrency());
                row.put("voucherNo", v.getVoucherNo());
                row.put("voucherName", v.getVoucherName());
                row.put("financeView", true);
            } else {
                putFullVersion(row, v);
            }
            result.add(row);
        }
        return result;
    }

    /** 协议的签约估值快照列表：角色收口同上 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> snapshots(Long agreementId, String operatorName, String operatorRole) {
        List<InsuranceValuationSnapshot> snapshots = snapshotMapper.selectList(
                new LambdaQueryWrapper<InsuranceValuationSnapshot>()
                        .eq(InsuranceValuationSnapshot::getAgreementId, agreementId)
                        .orderByAsc(InsuranceValuationSnapshot::getId));
        if (snapshots.isEmpty()) {
            return new ArrayList<>();
        }
        Long relicId = snapshots.get(0).getRelicId();
        denyIfSensitiveRole(operatorRole, operatorName, relicId, "AGR#" + agreementId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (InsuranceValuationSnapshot s : snapshots) {
            result.add(toRoleView(s, operatorName, operatorRole, true));
        }
        return result;
    }

    // ---- 过期提醒 ----

    /**
     * 扫描 ACTIVE 且有效期已过、尚无提醒的估值，每版本生成一条 OPEN 提醒并留痕。
     * 只提示：不改变估值/借展/归还任何状态。逐条独立事务由调度器承担。
     */
    @Transactional
    public InsuranceValuationReminder remindIfExpired(Long versionId, LocalDateTime now) {
        InsuranceValuationVersion v = versionMapper.selectById(versionId);
        if (v == null || !VAL_ACTIVE.equals(v.getStatus())) {
            return null;
        }
        if (v.getEffectiveTo() == null || !v.getEffectiveTo().isBefore(now)) {
            return null;
        }
        InsuranceValuationReminder exist = reminderMapper.selectOne(
                new LambdaQueryWrapper<InsuranceValuationReminder>()
                        .eq(InsuranceValuationReminder::getValuationVersionId, versionId)
                        .last("LIMIT 1"));
        if (exist != null) {
            return exist;
        }
        InsuranceValuationReminder r = new InsuranceValuationReminder();
        r.setReminderNo(nextReminderNo());
        r.setRelicId(v.getRelicId());
        r.setValuationVersionId(v.getId());
        r.setValuationNo(v.getValuationNo());
        r.setExpiredAt(v.getEffectiveTo());
        r.setRemindedAt(now);
        r.setStatus(VAL_REMIND_OPEN);
        reminderMapper.insert(r);
        ledgerService.append(v.getRelicId(), null, null,
                ACTION_VAL_EXPIRE_REMIND, r.getReminderNo(),
                "保险估值 " + v.getValuationNo() + " V" + v.getVersionNo()
                        + " 已于 " + v.getEffectiveTo() + " 到期，自动提醒续保/重新评估；"
                        + "提醒不冻结借展归还，在借文物按签约快照继续履约", now);
        return r;
    }

    /** 列出待处理/全部过期提醒 */
    @Transactional(readOnly = true)
    public List<InsuranceValuationReminder> listReminders(String status) {
        LambdaQueryWrapper<InsuranceValuationReminder> q =
                new LambdaQueryWrapper<InsuranceValuationReminder>()
                        .orderByDesc(InsuranceValuationReminder::getRemindedAt);
        if (status != null && !status.isEmpty()) {
            q.eq(InsuranceValuationReminder::getStatus, status);
        }
        return reminderMapper.selectList(q);
    }

    /** 供调度器取数：全部 ACTIVE 版本（过期判定在 remindIfExpired 内，逐版本独立事务） */
    @Transactional(readOnly = true)
    public List<InsuranceValuationVersion> listActiveVersions() {
        return versionMapper.selectList(new LambdaQueryWrapper<InsuranceValuationVersion>()
                .eq(InsuranceValuationVersion::getStatus, VAL_ACTIVE));
    }

    /** 人工确认过期提醒：只关提醒，不改估值状态、不影响归还 */
    @Transactional
    public InsuranceValuationReminder acknowledgeReminder(Long reminderId,
                                                          ValuationReminderAckRequest req) {
        InsuranceValuationReminder r = reminderMapper.selectById(reminderId);
        if (r == null) {
            throw new IllegalArgumentException("过期提醒不存在: id=" + reminderId);
        }
        if (VAL_REMIND_ACKED.equals(r.getStatus())) {
            throw new IllegalArgumentException("提醒已确认，不能重复确认");
        }
        LocalDateTime now = LocalDateTime.now();
        r.setStatus(VAL_REMIND_ACKED);
        r.setAcknowledgedBy(req.getAcknowledgedBy());
        r.setAcknowledgedAt(now);
        r.setAckNote(req.getAckNote());
        reminderMapper.updateById(r);
        ledgerService.append(r.getRelicId(), null, null,
                ACTION_VAL_REMIND_ACK, r.getReminderNo(),
                        "估值过期提醒由 " + req.getAcknowledgedBy() + " 确认"
                        + (req.getAckNote() == null ? "" : "：" + req.getAckNote())
                        + "；确认不改变估值状态、不冻结借展归还", now);
        return r;
    }

    // ---- 视图组装 / 角色收口 ----

    private Map<String, Object> toRoleView(InsuranceValuationVersion v, String operatorName,
                                           String role, boolean loanSnapshot) {
        denyIfSensitiveRole(role, operatorName, v.getRelicId(), v.getValuationNo());
        Map<String, Object> view = new LinkedHashMap<>();
        if (ROLE_FINANCE.equals(role)) {
            // 财务：只能看金额与凭证，来源/评估机构/有效期等敏感字段一律不下发
            view.put("relicId", v.getRelicId());
            view.put("valuationNo", v.getValuationNo());
            view.put("versionNo", v.getVersionNo());
            view.put("amount", v.getAmount());
            view.put("currency", v.getCurrency());
            view.put("voucherNo", v.getVoucherNo());
            view.put("voucherName", v.getVoucherName());
            view.put("financeView", true);
            return view;
        }
        putFullVersion(view, v);
        return view;
    }

    private Map<String, Object> toRoleView(InsuranceValuationSnapshot s, String operatorName,
                                           String role, boolean loanSnapshot) {
        denyIfSensitiveRole(role, operatorName, s.getRelicId(), s.getSnapshotNo());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("agreementId", s.getAgreementId());
        view.put("agreementNo", s.getAgreementNo());
        view.put("snapshotNo", s.getSnapshotNo());
        view.put("generatedAt", s.getGeneratedAt());
        if (ROLE_FINANCE.equals(role)) {
            // 财务借展视角：只见签约快照金额与凭证
            view.put("relicId", s.getRelicId());
            view.put("amount", s.getAmount());
            view.put("currency", s.getCurrency());
            view.put("voucherNo", s.getVoucherNo());
            view.put("financeView", true);
            return view;
        }
        view.put("relicId", s.getRelicId());
        view.put("valuationVersionId", s.getValuationVersionId());
        view.put("versionNo", s.getVersionNo());
        view.put("sourceType", s.getSourceType());
        view.put("sourceDesc", s.getSourceDesc());
        view.put("appraisalOrg", s.getAppraisalOrg());
        view.put("amount", s.getAmount());
        view.put("currency", s.getCurrency());
        view.put("effectiveFrom", s.getEffectiveFrom());
        view.put("effectiveTo", s.getEffectiveTo());
        view.put("voucherNo", s.getVoucherNo());
        view.put("borrower", s.getBorrower());
        view.put("signedBy", s.getSignedBy());
        view.put("note", s.getNote());
        view.put("loanSnapshot", true);
        return view;
    }

    /**
     * 公众与普通馆员不能访问敏感估值字段：直接拒绝（不返回脱敏空壳），
     * 拒绝流水走独立事务落库（放行/拒绝都可在台账追溯）。
     */
    private void denyIfSensitiveRole(String role, String operatorName, Long relicId, String bizNo) {
        if (ROLE_PUBLIC.equals(role) || ROLE_LIBRARIAN.equals(role)) {
            String reason = "保险估值属于敏感字段，公众与普通馆员无权访问（当前角色「"
                    + role + "」，访问人 " + operatorName + "）";
            // 独立事务：外层只读事务或后续拒绝异常回滚都不丢这条拒绝流水
            accessAuditService.recordDeny(relicId, bizNo, reason + "，入口拒绝并留痕");
            throw new IllegalArgumentException(reason);
        }
    }

    private void putFullVersion(Map<String, Object> view, InsuranceValuationVersion v) {
        view.put("id", v.getId());
        view.put("relicId", v.getRelicId());
        view.put("valuationNo", v.getValuationNo());
        view.put("versionNo", v.getVersionNo());
        view.put("sourceType", v.getSourceType());
        view.put("sourceDesc", v.getSourceDesc());
        view.put("appraisalOrg", v.getAppraisalOrg());
        view.put("amount", v.getAmount());
        view.put("currency", v.getCurrency());
        view.put("effectiveFrom", v.getEffectiveFrom());
        view.put("effectiveTo", v.getEffectiveTo());
        view.put("voucherNo", v.getVoucherNo());
        view.put("voucherName", v.getVoucherName());
        view.put("status", v.getStatus());
        view.put("changeReason", v.getChangeReason());
        view.put("submittedBy", v.getSubmittedBy());
        view.put("submittedAt", v.getSubmittedAt());
        view.put("firstApprover", v.getFirstApprover());
        view.put("firstApprovedAt", v.getFirstApprovedAt());
        view.put("firstOpinion", v.getFirstOpinion());
        view.put("secondApprover", v.getSecondApprover());
        view.put("secondApprovedAt", v.getSecondApprovedAt());
        view.put("endorsementId", v.getEndorsementId());
        view.put("activatedAt", v.getActivatedAt());
        view.put("rejectedBy", v.getRejectedBy());
        view.put("rejectReason", v.getRejectReason());
    }

    // ---- 内部 ----

    private void validateFields(String sourceType, String currency,
                                LocalDateTime from, LocalDateTime to) {
        if (!SOURCE_TYPES.contains(sourceType)) {
            throw new IllegalArgumentException(
                    "估值来源只能为 PURCHASE/APPRAISAL/AUCTION/DONATION/OTHER");
        }
        if (!CURRENCIES.contains(currency)) {
            throw new IllegalArgumentException("币种只能为 CNY/USD/EUR/JPY/GBP/HKD");
        }
        if (from == null || to == null || !to.isAfter(from)) {
            throw new IllegalArgumentException("估值有效期止必须晚于有效期起");
        }
    }

    private InsuranceValuationVersion activeVersionOf(Long relicId) {
        return versionMapper.selectList(new LambdaQueryWrapper<InsuranceValuationVersion>()
                        .eq(InsuranceValuationVersion::getRelicId, relicId)
                        .eq(InsuranceValuationVersion::getStatus, VAL_ACTIVE)
                        .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
    }

    private InsuranceValuationSnapshot latestSnapshotOfAgreement(Long agreementId) {
        return snapshotMapper.selectList(new LambdaQueryWrapper<InsuranceValuationSnapshot>()
                        .eq(InsuranceValuationSnapshot::getAgreementId, agreementId)
                        .orderByDesc(InsuranceValuationSnapshot::getId)
                        .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
    }

    private InsuranceValuationVersion requireVersion(Long id) {
        InsuranceValuationVersion v = versionMapper.selectById(id);
        if (v == null) {
            throw new IllegalArgumentException("保险估值版本不存在: id=" + id);
        }
        return v;
    }

    private int nextVersionNo(Long relicId) {
        return versionMapper.selectList(new LambdaQueryWrapper<InsuranceValuationVersion>()
                        .eq(InsuranceValuationVersion::getRelicId, relicId)
                        .orderByDesc(InsuranceValuationVersion::getVersionNo)
                        .last("LIMIT 1"))
                .stream().findFirst().map(InsuranceValuationVersion::getVersionNo).orElse(0) + 1;
    }

    private String nextValuationNo() {
        return String.format("VAL-%06d", versionMapper.nextValuationSeq());
    }

    private String nextEndorsementNo() {
        return String.format("END-%06d", endorsementMapper.nextEndorsementSeq());
    }

    private String nextSnapshotNo() {
        return String.format("VLS-%06d", snapshotMapper.nextSnapshotSeq());
    }

    private String nextReminderNo() {
        return String.format("VLR-%06d", reminderMapper.nextReminderSeq());
    }

    private String sourceName(String code) {
        switch (code) {
            case "PURCHASE": return "购藏";
            case "APPRAISAL": return "评估";
            case "AUCTION": return "拍卖";
            case "DONATION": return "捐赠";
            default: return "其他";
        }
    }

    private String stepName(String step) {
        return VAL_APPROVAL_SECOND.equals(step) ? "复核" : "初审";
    }
}
