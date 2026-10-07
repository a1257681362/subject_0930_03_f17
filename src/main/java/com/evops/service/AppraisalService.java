package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.dto.AppraisalAttachmentItem;
import com.evops.dto.AppraisalCaseCreateRequest;
import com.evops.dto.ArbitrationConcludeRequest;
import com.evops.dto.OpinionCorrectionRequest;
import com.evops.dto.OpinionOperatorRequest;
import com.evops.dto.OpinionSubmitRequest;
import com.evops.entity.AppraisalArbitration;
import com.evops.entity.AppraisalAttachment;
import com.evops.entity.AppraisalCase;
import com.evops.entity.AppraisalCorrection;
import com.evops.entity.AppraisalOpinion;
import com.evops.entity.AppraisalReportAudit;
import com.evops.entity.Relic;
import com.evops.mapper.AppraisalArbitrationMapper;
import com.evops.mapper.AppraisalAttachmentMapper;
import com.evops.mapper.AppraisalCaseMapper;
import com.evops.mapper.AppraisalCorrectionMapper;
import com.evops.mapper.AppraisalOpinionMapper;
import com.evops.mapper.AppraisalReportAuditMapper;
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
 * 文物鉴定意见：
 * <ul>
 *   <li>一案串联初鉴（INITIAL）与复鉴（REVIEW），两轮意见分别固化专家、依据、
 *       置信说明与附件；任何一轮都不覆盖另一轮。</li>
 *   <li>意见先存 DRAFT：专家本人可撤回（置 WITHDRAWN 留痕）；发布后 PUBLISHED
 *       原文锁定，只能通过 t_appraisal_correction 追加更正。</li>
 *   <li>复鉴发布时与已发布初鉴比对：结论一致（含暂不定论）直接定稿；
 *       结论冲突案件进入 ARBITRATING 并自动开立仲裁单，绝不取最后一条覆盖。</li>
 *   <li>仲裁人采初鉴/采复鉴/综合调整后定稿，案件头快照最终结论与对外摘要。</li>
 *   <li>普通馆员只能读结论摘要；详细报告下载由 {@link AppraisalReportAuditService}
 *       逐条写审计（放行/拒绝都留痕，拒绝流水独立事务不回滚）。</li>
 * </ul>
 */
@Service
public class AppraisalService {

    private static final List<String> CONCLUSION_CODES = Arrays.asList(
            CONCLUSION_AUTHENTIC, CONCLUSION_COUNTERFEIT,
            CONCLUSION_PARTIAL, CONCLUSION_INCONCLUSIVE);

    /** 对外结论摘要落库上限（t_appraisal_case.conclusion_summary VARCHAR(500)） */
    private static final int SUMMARY_MAX_LEN = 500;

    private final AppraisalCaseMapper caseMapper;
    private final AppraisalOpinionMapper opinionMapper;
    private final AppraisalCorrectionMapper correctionMapper;
    private final AppraisalArbitrationMapper arbitrationMapper;
    private final AppraisalAttachmentMapper attachmentMapper;
    private final AppraisalReportAuditMapper reportAuditMapper;
    private final AppraisalReportAuditService reportAuditService;
    private final RelicService relicService;
    private final LedgerService ledgerService;

    public AppraisalService(AppraisalCaseMapper caseMapper,
                            AppraisalOpinionMapper opinionMapper,
                            AppraisalCorrectionMapper correctionMapper,
                            AppraisalArbitrationMapper arbitrationMapper,
                            AppraisalAttachmentMapper attachmentMapper,
                            AppraisalReportAuditMapper reportAuditMapper,
                            AppraisalReportAuditService reportAuditService,
                            RelicService relicService,
                            LedgerService ledgerService) {
        this.caseMapper = caseMapper;
        this.opinionMapper = opinionMapper;
        this.correctionMapper = correctionMapper;
        this.arbitrationMapper = arbitrationMapper;
        this.attachmentMapper = attachmentMapper;
        this.reportAuditMapper = reportAuditMapper;
        this.reportAuditService = reportAuditService;
        this.relicService = relicService;
        this.ledgerService = ledgerService;
    }

    // ---- 案件 ----

    @Transactional
    public AppraisalCase openCase(AppraisalCaseCreateRequest req) {
        relicService.requireActiveRelic(req.getRelicId());

        LocalDateTime now = LocalDateTime.now();
        AppraisalCase appraisalCase = new AppraisalCase();
        appraisalCase.setCaseNo(nextCaseNo());
        appraisalCase.setRelicId(req.getRelicId());
        appraisalCase.setProjectId(req.getProjectId());
        appraisalCase.setTitle(req.getTitle());
        appraisalCase.setStatus(APPRAISAL_OPEN);
        caseMapper.insert(appraisalCase);

        ledgerService.append(req.getRelicId(), req.getProjectId(), null,
                ACTION_APPRAISAL_OPEN, appraisalCase.getCaseNo(),
                "开立鉴定案件：" + req.getTitle() + "，开立人 " + req.getOpenedBy(), now);
        return appraisalCase;
    }

    // ---- 意见：提交 / 发布 / 撤回 / 更正 ----

    /**
     * 提交初鉴/复鉴意见。publish=false 存为未发布草稿；publish=true 直接发布，
     * 复鉴发布即触发与初鉴的一致性判定（冲突进仲裁）。
     */
    @Transactional
    public AppraisalOpinion submitOpinion(Long caseId, OpinionSubmitRequest req) {
        AppraisalCase appraisalCase = requireCase(caseId);
        String stage = req.getStage();
        if (!APPRAISAL_STAGE_INITIAL.equals(stage) && !APPRAISAL_STAGE_REVIEW.equals(stage)) {
            throw new IllegalArgumentException("鉴定轮次只能为 INITIAL（初鉴）/REVIEW（复鉴）");
        }
        if (APPRAISAL_CONCLUDED.equals(appraisalCase.getStatus())) {
            throw new IllegalArgumentException("案件已定稿，不能再提交鉴定意见: " + appraisalCase.getCaseNo());
        }
        if (APPRAISAL_ARBITRATING.equals(appraisalCase.getStatus())) {
            throw new IllegalArgumentException("案件已进入仲裁，初鉴/复鉴均冻结，不能再提交或覆盖意见，"
                    + "请由仲裁人定稿: " + appraisalCase.getCaseNo());
        }
        if (!CONCLUSION_CODES.contains(req.getConclusionCode())) {
            throw new IllegalArgumentException(
                    "结论码只能为 AUTHENTIC/COUNTERFEIT/PARTIAL/INCONCLUSIVE");
        }
        if (APPRAISAL_STAGE_INITIAL.equals(stage)
                && !APPRAISAL_OPEN.equals(appraisalCase.getStatus())) {
            throw new IllegalArgumentException("初鉴意见只能在待初鉴（OPEN）阶段提交");
        }
        if (APPRAISAL_STAGE_REVIEW.equals(stage)
                && !APPRAISAL_REVIEWING.equals(appraisalCase.getStatus())) {
            throw new IllegalArgumentException("复鉴意见只能在待复鉴（REVIEWING）阶段提交；"
                    + "初鉴尚未发布或案件已进入仲裁");
        }
        Long draftCount = opinionMapper.selectCount(new LambdaQueryWrapper<AppraisalOpinion>()
                .eq(AppraisalOpinion::getCaseId, caseId)
                .eq(AppraisalOpinion::getStage, stage)
                .eq(AppraisalOpinion::getStatus, OPINION_DRAFT));
        if (draftCount != null && draftCount > 0) {
            throw new IllegalArgumentException("该轮次存在未发布草稿，请先发布或撤回后再提交");
        }

        int round = nextRoundNo(caseId, stage);
        LocalDateTime now = LocalDateTime.now();
        AppraisalOpinion opinion = new AppraisalOpinion();
        opinion.setCaseId(caseId);
        opinion.setStage(stage);
        opinion.setRoundNo(round);
        opinion.setExpertName(req.getExpertName());
        opinion.setExpertTitle(req.getExpertTitle());
        opinion.setBasis(req.getBasis());
        opinion.setConfidenceNote(req.getConfidenceNote());
        opinion.setConclusionCode(req.getConclusionCode());
        opinion.setConclusionText(req.getConclusionText());
        opinion.setSubmittedBy(req.getSubmittedBy());
        opinion.setSubmittedAt(now);
        opinion.setStatus(req.isPublish() ? OPINION_PUBLISHED : OPINION_DRAFT);
        if (req.isPublish()) {
            opinion.setPublishedAt(now);
        }
        opinionMapper.insert(opinion);
        saveAttachments(req.getAttachments(), opinion.getId(), null, null,
                "OPINION", req.getSubmittedBy(), now);

        if (req.isPublish()) {
            afterPublish(appraisalCase, opinion, now);
        } else {
            ledgerService.append(appraisalCase.getRelicId(), appraisalCase.getProjectId(), null,
                    ACTION_APPRAISAL_DRAFT, appraisalCase.getCaseNo(),
                    stageName(stage) + "第" + round + "轮意见已存为未发布草稿，专家："
                            + req.getExpertName(), now);
        }
        return opinion;
    }

    /** 发布未发布草稿：只能由意见专家本人发布 */
    @Transactional
    public AppraisalOpinion publishOpinion(Long opinionId, OpinionOperatorRequest req) {
        AppraisalOpinion opinion = requireOpinion(opinionId);
        if (!OPINION_DRAFT.equals(opinion.getStatus())) {
            throw new IllegalArgumentException("意见不是未发布草稿，不能发布（当前状态 "
                    + opinion.getStatus() + "）");
        }
        if (!opinion.getExpertName().equals(req.getOperatorName())) {
            throw new IllegalArgumentException("只能由意见专家本人发布：该意见专家为 "
                    + opinion.getExpertName());
        }
        AppraisalCase appraisalCase = requireCase(opinion.getCaseId());
        if (APPRAISAL_CONCLUDED.equals(appraisalCase.getStatus())
                || APPRAISAL_ARBITRATING.equals(appraisalCase.getStatus())) {
            throw new IllegalArgumentException("案件已"
                    + (APPRAISAL_CONCLUDED.equals(appraisalCase.getStatus()) ? "定稿" : "进入仲裁")
                    + "，草稿不能再发布: " + appraisalCase.getCaseNo());
        }

        LocalDateTime now = LocalDateTime.now();
        opinion.setStatus(OPINION_PUBLISHED);
        opinion.setPublishedAt(now);
        opinionMapper.updateById(opinion);
        afterPublish(appraisalCase, opinion, now);
        return opinion;
    }

    /** 撤回未发布意见：仅草稿可撤，置 WITHDRAWN 留痕，不删行、不删附件 */
    @Transactional
    public AppraisalOpinion withdrawOpinion(Long opinionId, OpinionOperatorRequest req) {
        AppraisalOpinion opinion = requireOpinion(opinionId);
        if (!OPINION_DRAFT.equals(opinion.getStatus())) {
            throw new IllegalArgumentException("已发布意见不能撤回，只能追加更正"
                    + "（当前状态 " + opinion.getStatus() + "）");
        }
        if (!opinion.getExpertName().equals(req.getOperatorName())) {
            throw new IllegalArgumentException("只能由意见专家本人撤回：该意见专家为 "
                    + opinion.getExpertName());
        }
        if (req.getReason() == null || req.getReason().trim().isEmpty()) {
            throw new IllegalArgumentException("撤回必须填写撤回原因");
        }

        LocalDateTime now = LocalDateTime.now();
        opinion.setStatus(OPINION_WITHDRAWN);
        opinion.setWithdrawnAt(now);
        opinion.setWithdrawReason(req.getReason().trim());
        opinionMapper.updateById(opinion);

        AppraisalCase appraisalCase = requireCase(opinion.getCaseId());
        ledgerService.append(appraisalCase.getRelicId(), appraisalCase.getProjectId(), null,
                ACTION_APPRAISAL_WITHDRAW, appraisalCase.getCaseNo(),
                stageName(opinion.getStage()) + "第" + opinion.getRoundNo()
                        + "轮未发布意见由专家 " + req.getOperatorName()
                        + " 撤回（留痕不删档），原因：" + req.getReason().trim(), now);
        return opinion;
    }

    /** 已发布意见追加更正：只追加，永不回写意见原文；必须原专家本人 */
    @Transactional
    public AppraisalCorrection addCorrection(Long opinionId, OpinionCorrectionRequest req) {
        AppraisalOpinion opinion = requireOpinion(opinionId);
        if (!OPINION_PUBLISHED.equals(opinion.getStatus())) {
            throw new IllegalArgumentException("只有已发布意见可以追加更正（当前状态 "
                    + opinion.getStatus() + "）");
        }
        if (!opinion.getExpertName().equals(req.getCorrectedBy())) {
            throw new IllegalArgumentException("只能由原意见专家本人追加更正：该意见专家为 "
                    + opinion.getExpertName());
        }
        AppraisalCase appraisalCase = requireCase(opinion.getCaseId());

        Integer maxNo = correctionMapper.selectList(new LambdaQueryWrapper<AppraisalCorrection>()
                        .eq(AppraisalCorrection::getOpinionId, opinionId)
                        .orderByDesc(AppraisalCorrection::getCorrectionNo)
                        .last("LIMIT 1"))
                .stream().findFirst().map(AppraisalCorrection::getCorrectionNo).orElse(0);
        LocalDateTime now = LocalDateTime.now();
        AppraisalCorrection correction = new AppraisalCorrection();
        correction.setOpinionId(opinionId);
        correction.setCorrectionNo(maxNo + 1);
        correction.setContent(req.getContent());
        correction.setCorrectedBy(req.getCorrectedBy());
        correction.setCorrectedAt(now);
        correctionMapper.insert(correction);
        saveAttachments(req.getAttachments(), opinionId, correction.getId(), null,
                "CORRECTION", req.getCorrectedBy(), now);

        ledgerService.append(appraisalCase.getRelicId(), appraisalCase.getProjectId(), null,
                ACTION_APPRAISAL_CORRECT, appraisalCase.getCaseNo(),
                stageName(opinion.getStage()) + "专家 " + req.getCorrectedBy()
                        + " 对第" + opinion.getRoundNo() + "轮已发布意见追加第"
                        + correction.getCorrectionNo() + "号更正（原文不改写）", now);
        return correction;
    }

    // ---- 仲裁 ----

    /**
     * 仲裁定稿：采初鉴/采复鉴时结论自对应意见带入；综合调整须显式给出最终结论。
     * 两轮意见均保持不变，最终结论只落在案件头与仲裁单上。
     */
    @Transactional
    public AppraisalArbitration concludeArbitration(Long arbitrationId,
                                                    ArbitrationConcludeRequest req) {
        AppraisalArbitration arbitration = requireArbitration(arbitrationId);
        if (!ARB_OPEN.equals(arbitration.getStatus())) {
            throw new IllegalArgumentException("仲裁单已定稿，不能重复仲裁: "
                    + arbitration.getArbitrationNo());
        }
        AppraisalCase appraisalCase = requireCase(arbitration.getCaseId());
        if (!APPRAISAL_ARBITRATING.equals(appraisalCase.getStatus())) {
            throw new IllegalArgumentException("案件当前不在仲裁中，不能仲裁定稿");
        }
        if (!arbitration.getArbiterName().equals(req.getArbiterName())) {
            throw new IllegalArgumentException("只能由指定仲裁人定稿：本单仲裁人为 "
                    + arbitration.getArbiterName());
        }

        String decision = req.getFinalDecision();
        String finalCode;
        String finalText;
        String finalBasis;
        String confidenceNote;
        Long sourceOpinionId;
        if (ARB_ADOPT_INITIAL.equals(decision)) {
            AppraisalOpinion initial = requireOpinion(arbitration.getInitialOpinionId());
            finalCode = initial.getConclusionCode();
            finalText = initial.getConclusionText();
            finalBasis = initial.getBasis();
            confidenceNote = initial.getConfidenceNote();
            sourceOpinionId = initial.getId();
        } else if (ARB_ADOPT_REVIEW.equals(decision)) {
            AppraisalOpinion review = requireOpinion(arbitration.getReviewOpinionId());
            finalCode = review.getConclusionCode();
            finalText = review.getConclusionText();
            finalBasis = review.getBasis();
            confidenceNote = review.getConfidenceNote();
            sourceOpinionId = review.getId();
        } else if (ARB_ADJUSTED.equals(decision)) {
            if (req.getFinalConclusionCode() == null
                    || !CONCLUSION_CODES.contains(req.getFinalConclusionCode())
                    || isBlank(req.getFinalConclusionText())
                    || isBlank(req.getFinalBasis())
                    || isBlank(req.getConfidenceNote())) {
                throw new IllegalArgumentException("综合调整（ADJUSTED）必须给出最终结论码、"
                        + "结论文本、鉴定依据与置信说明");
            }
            finalCode = req.getFinalConclusionCode();
            finalText = req.getFinalConclusionText();
            finalBasis = req.getFinalBasis();
            confidenceNote = req.getConfidenceNote();
            sourceOpinionId = null;
        } else {
            throw new IllegalArgumentException(
                    "仲裁决定只能为 ADOPT_INITIAL/ADOPT_REVIEW/ADJUSTED");
        }

        LocalDateTime now = LocalDateTime.now();
        arbitration.setStatus(ARB_CONCLUDED);
        arbitration.setFinalDecision(decision);
        arbitration.setFinalConclusionCode(finalCode);
        arbitration.setFinalConclusionText(finalText);
        arbitration.setFinalBasis(finalBasis);
        arbitration.setConfidenceNote(confidenceNote);
        arbitration.setConcludedAt(now);
        arbitrationMapper.updateById(arbitration);
        saveAttachments(req.getAttachments(), null, null, arbitration.getId(),
                "ARBITRATION", req.getArbiterName(), now);

        appraisalCase.setStatus(APPRAISAL_CONCLUDED);
        appraisalCase.setFinalStage(APPRAISAL_STAGE_ARBITRATION);
        appraisalCase.setFinalOpinionId(sourceOpinionId);
        appraisalCase.setFinalBy(req.getArbiterName());
        appraisalCase.setFinalAt(now);
        appraisalCase.setConclusionSummary(buildSummary(finalCode, finalText));
        caseMapper.updateById(appraisalCase);

        ledgerService.append(appraisalCase.getRelicId(), appraisalCase.getProjectId(), null,
                ACTION_APPRAISAL_ARB_CONCLUDE, appraisalCase.getCaseNo(),
                "仲裁人 " + req.getArbiterName() + " 定稿：" + decisionName(decision)
                        + "，最终结论「" + conclusionName(finalCode) + "」，两轮意见原样保留", now);
        ledgerService.append(appraisalCase.getRelicId(), appraisalCase.getProjectId(), null,
                ACTION_APPRAISAL_CONCLUDE, appraisalCase.getCaseNo(),
                "鉴定案件定稿，最终结论：" + conclusionName(finalCode), now);
        return arbitration;
    }

    // ---- 查询：全量 / 摘要 / 报告下载 ----

    /**
     * 案件全量详情：专家/仲裁视角，含两轮意见、更正、附件、仲裁单。
     * 普通馆员不得使用本视图，应调用 {@link #summary}。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long caseId) {
        AppraisalCase appraisalCase = requireCase(caseId);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("case", appraisalCase);
        detail.put("initialOpinions", opinionsDetail(caseId, APPRAISAL_STAGE_INITIAL));
        detail.put("reviewOpinions", opinionsDetail(caseId, APPRAISAL_STAGE_REVIEW));
        detail.put("arbitration", arbitrationDetail(latestArbitrationOfCase(caseId)));
        return detail;
    }

    /**
     * 普通馆员视角：只返回案件头与结论摘要，不含任何专家、依据、置信说明、附件。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> summary(Long caseId) {
        AppraisalCase appraisalCase = requireCase(caseId);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("caseId", appraisalCase.getId());
        summary.put("caseNo", appraisalCase.getCaseNo());
        summary.put("relicId", appraisalCase.getRelicId());
        summary.put("title", appraisalCase.getTitle());
        summary.put("status", appraisalCase.getStatus());
        summary.put("finalStage", appraisalCase.getFinalStage());
        summary.put("conclusionSummary", appraisalCase.getConclusionSummary());
        return summary;
    }

    /**
     * 详细报告下载：仅鉴定专家与仲裁人可获取全量明细；普通馆员等其他角色拒绝。
     * 无论放行/拒绝都由独立事务写一条下载审计流水。
     */
    @Transactional
    public Map<String, Object> downloadReport(Long caseId, String operatorName,
                                              String operatorRole, String clientIp) {
        AppraisalCase appraisalCase = requireCase(caseId);
        boolean granted = ROLE_APPRAISAL_EXPERT.equals(operatorRole)
                || ROLE_ARBITER.equals(operatorRole);
        String reason = granted
                ? null
                : "仅鉴定专家/仲裁人可下载详细报告，当前角色「" + operatorRole
                        + "」仅可查看结论摘要";
        // 审计流水独立事务：放行与拒绝都保留，拒绝时主事务即便回滚也不丢这条
        AppraisalReportAudit audit = reportAuditService.record(
                caseId, appraisalCase.getRelicId(), appraisalCase.getCaseNo(),
                operatorName, operatorRole, granted, reason, clientIp);
        if (!granted) {
            throw new IllegalArgumentException(reason);
        }
        Map<String, Object> report = detail(caseId);
        report.put("downloadAuditId", audit.getId());
        report.put("downloadedAt", audit.getDownloadedAt());
        return report;
    }

    /** 报告下载审计流水查询（管理/审计视角） */
    @Transactional(readOnly = true)
    public List<AppraisalReportAudit> listReportAudits(Long caseId) {
        requireCase(caseId);
        return reportAuditMapper.selectList(new LambdaQueryWrapper<AppraisalReportAudit>()
                .eq(AppraisalReportAudit::getCaseId, caseId)
                .orderByAsc(AppraisalReportAudit::getId));
    }

    // ---- 内部 ----

    /** 发布后的统一推进：初鉴发布 -> REVIEWING；复鉴发布 -> 定稿或进仲裁 */
    private void afterPublish(AppraisalCase appraisalCase, AppraisalOpinion opinion,
                              LocalDateTime now) {
        ledgerService.append(appraisalCase.getRelicId(), appraisalCase.getProjectId(), null,
                ACTION_APPRAISAL_PUBLISH, appraisalCase.getCaseNo(),
                stageName(opinion.getStage()) + "第" + opinion.getRoundNo()
                        + "轮意见发布，专家 " + opinion.getExpertName()
                        + "，结论「" + conclusionName(opinion.getConclusionCode()) + "」", now);

        if (APPRAISAL_STAGE_INITIAL.equals(opinion.getStage())) {
            appraisalCase.setStatus(APPRAISAL_REVIEWING);
            caseMapper.updateById(appraisalCase);
            return;
        }

        // 复鉴发布：与已发布初鉴比对——禁止最后一条覆盖，冲突必须进仲裁
        AppraisalOpinion initial = latestPublished(appraisalCase.getId(), APPRAISAL_STAGE_INITIAL);
        if (initial == null) {
            throw new IllegalArgumentException("缺少已发布的初鉴意见，复鉴不能发布");
        }
        if (conflicting(initial.getConclusionCode(), opinion.getConclusionCode())) {
            openArbitration(appraisalCase, initial, opinion, now);
        } else {
            concludeCase(appraisalCase, APPRAISAL_STAGE_REVIEW, opinion,
                    opinion.getExpertName(), now);
        }
    }

    /**
     * 结论是否冲突：双方结论码不同且都不是 INCONCLUSIVE 才算冲突。
     * 暂不定论（INCONCLUSIVE）不构成对立结论，按复鉴结论定稿，无需仲裁。
     */
    private boolean conflicting(String initialCode, String reviewCode) {
        return !initialCode.equals(reviewCode)
                && !CONCLUSION_INCONCLUSIVE.equals(initialCode)
                && !CONCLUSION_INCONCLUSIVE.equals(reviewCode);
    }

    private void openArbitration(AppraisalCase appraisalCase, AppraisalOpinion initial,
                                 AppraisalOpinion review, LocalDateTime now) {
        // 复查：同一案件不能重复开立进行中的仲裁单
        AppraisalArbitration exist = openArbitrationOfCase(appraisalCase.getId());
        if (exist != null) {
            throw new IllegalArgumentException("案件已存在进行中的仲裁单: "
                    + exist.getArbitrationNo());
        }
        AppraisalArbitration arbitration = new AppraisalArbitration();
        arbitration.setArbitrationNo(nextArbitrationNo());
        arbitration.setCaseId(appraisalCase.getId());
        arbitration.setInitialOpinionId(initial.getId());
        arbitration.setReviewOpinionId(review.getId());
        arbitration.setConflictSummary("初鉴第" + initial.getRoundNo() + "轮结论「"
                + conclusionName(initial.getConclusionCode()) + "」（专家 "
                + initial.getExpertName() + "）与复鉴第" + review.getRoundNo()
                + "轮结论「" + conclusionName(review.getConclusionCode())
                + "」（专家 " + review.getExpertName() + "）冲突，进入仲裁；"
                + "两轮意见均保留，不得以最后一条覆盖");
        arbitration.setStatus(ARB_OPEN);
        arbitration.setArbiterName(defaultArbiterName());
        arbitration.setOpenedBy("system");
        arbitration.setOpenedAt(now);
        arbitrationMapper.insert(arbitration);

        appraisalCase.setStatus(APPRAISAL_ARBITRATING);
        caseMapper.updateById(appraisalCase);

        ledgerService.append(appraisalCase.getRelicId(), appraisalCase.getProjectId(), null,
                ACTION_APPRAISAL_ARB_OPEN, appraisalCase.getCaseNo(),
                "初鉴与复鉴结论冲突，自动开立仲裁单 " + arbitration.getArbitrationNo()
                        + "，仲裁人 " + arbitration.getArbiterName(), now);
    }

    private void concludeCase(AppraisalCase appraisalCase, String finalStage,
                              AppraisalOpinion opinion, String finalBy, LocalDateTime now) {
        appraisalCase.setStatus(APPRAISAL_CONCLUDED);
        appraisalCase.setFinalStage(finalStage);
        appraisalCase.setFinalOpinionId(opinion == null ? null : opinion.getId());
        appraisalCase.setFinalBy(finalBy);
        appraisalCase.setFinalAt(now);
        appraisalCase.setConclusionSummary(
                opinion == null ? null : buildSummary(opinion.getConclusionCode(),
                        opinion.getConclusionText()));
        caseMapper.updateById(appraisalCase);
        ledgerService.append(appraisalCase.getRelicId(), appraisalCase.getProjectId(), null,
                ACTION_APPRAISAL_CONCLUDE, appraisalCase.getCaseNo(),
                "鉴定案件定稿，最终结论："
                        + (opinion == null ? "" : conclusionName(opinion.getConclusionCode())), now);
    }

    private List<Map<String, Object>> opinionsDetail(Long caseId, String stage) {
        List<AppraisalOpinion> opinions = opinionMapper.selectList(
                new LambdaQueryWrapper<AppraisalOpinion>()
                        .eq(AppraisalOpinion::getCaseId, caseId)
                        .eq(AppraisalOpinion::getStage, stage)
                        .orderByAsc(AppraisalOpinion::getRoundNo));
        List<Map<String, Object>> result = new ArrayList<>();
        for (AppraisalOpinion opinion : opinions) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("opinion", opinion);
            List<AppraisalCorrection> corrections = correctionMapper.selectList(
                    new LambdaQueryWrapper<AppraisalCorrection>()
                            .eq(AppraisalCorrection::getOpinionId, opinion.getId())
                            .orderByAsc(AppraisalCorrection::getCorrectionNo));
            row.put("corrections", corrections);
            List<Long> correctionIds = new ArrayList<>();
            for (AppraisalCorrection c : corrections) {
                correctionIds.add(c.getId());
            }
            List<AppraisalAttachment> attachments = new ArrayList<>(
                    listAttachments(opinion.getId(), null, null));
            if (!correctionIds.isEmpty()) {
                attachments.addAll(attachmentMapper.selectList(
                        new LambdaQueryWrapper<AppraisalAttachment>()
                                .in(AppraisalAttachment::getCorrectionId, correctionIds)
                                .orderByAsc(AppraisalAttachment::getId)));
            }
            row.put("attachments", attachments);
            result.add(row);
        }
        return result;
    }

    private Map<String, Object> arbitrationDetail(AppraisalArbitration arbitration) {
        if (arbitration == null) {
            return null;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("arbitration", arbitration);
        row.put("attachments",
                listAttachments(null, null, arbitration.getId()));
        return row;
    }

    private List<AppraisalAttachment> listAttachments(Long opinionId, Long correctionId,
                                                      Long arbitrationId) {
        return attachmentMapper.selectList(new LambdaQueryWrapper<AppraisalAttachment>()
                .eq(opinionId != null, AppraisalAttachment::getOpinionId, opinionId)
                .eq(correctionId != null, AppraisalAttachment::getCorrectionId, correctionId)
                .eq(arbitrationId != null, AppraisalAttachment::getArbitrationId, arbitrationId)
                .orderByAsc(AppraisalAttachment::getId));
    }

    private void saveAttachments(List<AppraisalAttachmentItem> items, Long opinionId,
                                 Long correctionId, Long arbitrationId,
                                 String bizType, String uploadedBy, LocalDateTime now) {
        if (items == null) {
            return;
        }
        for (AppraisalAttachmentItem item : items) {
            AppraisalAttachment attachment = new AppraisalAttachment();
            attachment.setOpinionId(opinionId);
            attachment.setCorrectionId(correctionId);
            attachment.setArbitrationId(arbitrationId);
            attachment.setFileName(item.getFileName());
            attachment.setFilePath(item.getFilePath());
            attachment.setFileHash(item.getFileHash());
            attachment.setBizType(bizType);
            attachment.setUploadedBy(uploadedBy);
            attachment.setUploadedAt(now);
            attachmentMapper.insert(attachment);
        }
    }

    private AppraisalOpinion latestPublished(Long caseId, String stage) {
        return opinionMapper.selectList(new LambdaQueryWrapper<AppraisalOpinion>()
                        .eq(AppraisalOpinion::getCaseId, caseId)
                        .eq(AppraisalOpinion::getStage, stage)
                        .eq(AppraisalOpinion::getStatus, OPINION_PUBLISHED)
                        .orderByDesc(AppraisalOpinion::getRoundNo)
                        .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
    }

    private AppraisalArbitration openArbitrationOfCase(Long caseId) {
        return arbitrationMapper.selectOne(new LambdaQueryWrapper<AppraisalArbitration>()
                .eq(AppraisalArbitration::getCaseId, caseId)
                .eq(AppraisalArbitration::getStatus, ARB_OPEN)
                .last("LIMIT 1"));
    }

    /** 案件最新一张仲裁单（不限状态：定稿后详情仍要能取到结果） */
    private AppraisalArbitration latestArbitrationOfCase(Long caseId) {
        return arbitrationMapper.selectList(new LambdaQueryWrapper<AppraisalArbitration>()
                        .eq(AppraisalArbitration::getCaseId, caseId)
                        .orderByDesc(AppraisalArbitration::getId)
                        .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
    }

    private int nextRoundNo(Long caseId, String stage) {
        return opinionMapper.selectList(new LambdaQueryWrapper<AppraisalOpinion>()
                        .eq(AppraisalOpinion::getCaseId, caseId)
                        .eq(AppraisalOpinion::getStage, stage)
                        .orderByDesc(AppraisalOpinion::getRoundNo)
                        .last("LIMIT 1"))
                .stream().findFirst().map(AppraisalOpinion::getRoundNo).orElse(0) + 1;
    }

    public AppraisalCase requireCase(Long caseId) {
        AppraisalCase appraisalCase = caseMapper.selectById(caseId);
        if (appraisalCase == null) {
            throw new IllegalArgumentException("鉴定案件不存在: id=" + caseId);
        }
        return appraisalCase;
    }

    public AppraisalOpinion requireOpinion(Long opinionId) {
        AppraisalOpinion opinion = opinionMapper.selectById(opinionId);
        if (opinion == null) {
            throw new IllegalArgumentException("鉴定意见不存在: id=" + opinionId);
        }
        return opinion;
    }

    public AppraisalArbitration requireArbitration(Long arbitrationId) {
        AppraisalArbitration arbitration = arbitrationMapper.selectById(arbitrationId);
        if (arbitration == null) {
            throw new IllegalArgumentException("仲裁单不存在: id=" + arbitrationId);
        }
        return arbitration;
    }

    private String nextCaseNo() {
        return String.format("AP-%06d", caseMapper.nextCaseSeq());
    }

    private String nextArbitrationNo() {
        return String.format("ARB-%06d", arbitrationMapper.nextArbitrationSeq());
    }

    /** 骨架未接入登录态与仲裁人排班，默认指定馆内首席鉴定专家，可在仲裁单上换人流程另行扩展 */
    private String defaultArbiterName() {
        return "首席鉴定专家（仲裁人）";
    }

    private String buildSummary(String conclusionCode, String conclusionText) {
        String text = "鉴定结论：" + conclusionName(conclusionCode)
                + "。" + (conclusionText == null ? "" : conclusionText);
        return text.length() > SUMMARY_MAX_LEN
                ? text.substring(0, SUMMARY_MAX_LEN) : text;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private String stageName(String stage) {
        return APPRAISAL_STAGE_REVIEW.equals(stage) ? "复鉴" : "初鉴";
    }

    private String conclusionName(String code) {
        if (code == null) {
            return "";
        }
        switch (code) {
            case CONCLUSION_AUTHENTIC: return "真";
            case CONCLUSION_COUNTERFEIT: return "伪";
            case CONCLUSION_PARTIAL: return "部分真";
            case CONCLUSION_INCONCLUSIVE: return "暂不定论";
            default: return code;
        }
    }

    private String decisionName(String decision) {
        switch (decision) {
            case ARB_ADOPT_INITIAL: return "采初鉴意见";
            case ARB_ADOPT_REVIEW: return "采复鉴意见";
            case ARB_ADJUSTED: return "综合调整";
            default: return decision;
        }
    }
}
