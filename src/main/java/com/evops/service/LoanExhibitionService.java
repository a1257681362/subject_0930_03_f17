package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.dto.AgreementAmendRequest;
import com.evops.dto.AgreementManifestItem;
import com.evops.dto.AgreementRegisterRequest;
import com.evops.dto.AgreementSignRequest;
import com.evops.dto.AnomalyResolveRequest;
import com.evops.dto.EscortNodeRequest;
import com.evops.dto.ReturnCheckItemDto;
import com.evops.dto.ReturnCheckRequest;
import com.evops.dto.ReturnCheckSuppRequest;
import com.evops.entity.EscortNode;
import com.evops.entity.LoanAgreement;
import com.evops.entity.LoanAgreementVersion;
import com.evops.entity.LoanManifestLine;
import com.evops.entity.LoanReturnCheck;
import com.evops.entity.LoanReturnCheckLine;
import com.evops.entity.LoanReturnCheckSupp;
import com.evops.entity.Relic;
import com.evops.entity.RelicLoan;
import com.evops.entity.RepairProject;
import com.evops.mapper.EscortNodeMapper;
import com.evops.mapper.LoanAgreementMapper;
import com.evops.mapper.LoanAgreementVersionMapper;
import com.evops.mapper.LoanManifestLineMapper;
import com.evops.mapper.LoanReturnCheckLineMapper;
import com.evops.mapper.LoanReturnCheckMapper;
import com.evops.mapper.LoanReturnCheckSuppMapper;
import com.evops.mapper.RelicLoanMapper;
import com.evops.mapper.RepairProjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.evops.constant.MuseumConstants.*;

/**
 * 文物借展协议与押运闭环（museum-6）：
 *
 * 协议登记（借展方/保险金额/运输路线/归还日期 + 借展清单）-> 签署生效
 *   -> 封箱/交接/到馆/开箱四个押运节点按序登记 -> 归还逐项验收。
 *
 * 规则口径：
 * 1) 协议变更只追加新版本（版本号协议内递增），新版本签署后旧版本置 SUPERSEDED，
 *    已签协议不可直接覆盖；存在未签新版本期间押运节点暂停。
 * 2) 任一押运节点登记异常，后续节点全部暂停，异常解除（登记解除人/说明）后才继续。
 * 3) 开箱必须两名不同见证人。
 * 4) 归还验收以封箱出库时签署版本的清单为「原借展清单」逐项比对，
 *    漏项/多项均拒绝；损伤/缺失差异逐行永久保留。
 * 5) 封箱即写一条 t_relic_loan（LENT）贯通记录，复用 museum-1 跨业务占用校验；
 *    验收闭环时同步置 RETURNED。
 * 6) 登记/签署/变更/节点/异常/解除/验收全部追加台账留痕，
 *    修复侧可由项目经文物反查协议原单、节点处理人与异常原因。
 */
@Service
public class LoanExhibitionService {

    /** 押运节点固定顺序：封箱 -> 交接 -> 到馆 -> 开箱 */
    private static final List<String> NODE_ORDER =
            Arrays.asList(NODE_SEAL, NODE_HANDOVER, NODE_ARRIVAL, NODE_OPEN_BOX);

    private final LoanAgreementMapper agreementMapper;
    private final LoanAgreementVersionMapper versionMapper;
    private final LoanManifestLineMapper manifestMapper;
    private final EscortNodeMapper nodeMapper;
    private final LoanReturnCheckMapper checkMapper;
    private final LoanReturnCheckLineMapper checkLineMapper;
    private final LoanReturnCheckSuppMapper checkSuppMapper;
    private final RelicLoanMapper relicLoanMapper;
    private final RepairProjectMapper projectMapper;
    private final RelicService relicService;
    private final LedgerService ledgerService;
    private final InsuranceValuationService valuationService;

    public LoanExhibitionService(LoanAgreementMapper agreementMapper,
                                 LoanAgreementVersionMapper versionMapper,
                                 LoanManifestLineMapper manifestMapper,
                                 EscortNodeMapper nodeMapper,
                                 LoanReturnCheckMapper checkMapper,
                                 LoanReturnCheckLineMapper checkLineMapper,
                                 LoanReturnCheckSuppMapper checkSuppMapper,
                                 RelicLoanMapper relicLoanMapper,
                                 RepairProjectMapper projectMapper,
                                 RelicService relicService,
                                 LedgerService ledgerService,
                                 InsuranceValuationService valuationService) {
        this.agreementMapper = agreementMapper;
        this.versionMapper = versionMapper;
        this.manifestMapper = manifestMapper;
        this.nodeMapper = nodeMapper;
        this.checkMapper = checkMapper;
        this.checkLineMapper = checkLineMapper;
        this.checkSuppMapper = checkSuppMapper;
        this.relicLoanMapper = relicLoanMapper;
        this.projectMapper = projectMapper;
        this.relicService = relicService;
        this.ledgerService = ledgerService;
        this.valuationService = valuationService;
    }

    // ------------------------------------------------------------------
    // 协议登记 / 签署 / 变更
    // ------------------------------------------------------------------

    /**
     * 协议登记：主单 DRAFT + v1 DRAFT + 随版清单。
     */
    @Transactional
    public LoanAgreement register(AgreementRegisterRequest req) {
        Relic relic = relicService.requireActiveRelic(req.getRelicId());

        if (agreementMapper.selectCount(new LambdaQueryWrapper<LoanAgreement>()
                .eq(LoanAgreement::getAgreementNo, req.getAgreementNo())) > 0) {
            throw new IllegalArgumentException("协议单号已存在: " + req.getAgreementNo());
        }
        // 同一文物同时只允许一条未闭环借展协议
        Long openCount = agreementMapper.selectCount(new LambdaQueryWrapper<LoanAgreement>()
                .eq(LoanAgreement::getRelicId, req.getRelicId())
                .notIn(LoanAgreement::getStatus, AGR_RETURNED, AGR_CANCELLED));
        if (openCount != null && openCount > 0) {
            throw new IllegalArgumentException("文物存在未闭环借展协议，不能重复登记: " + relic.getRelicNo());
        }
        // 旧 museum-1 借出通道仍在途时也不能走借展协议，防止双通道占用
        Long openLoanCount = relicLoanMapper.selectCount(new LambdaQueryWrapper<RelicLoan>()
                .eq(RelicLoan::getRelicId, req.getRelicId())
                .eq(RelicLoan::getStatus, LOAN_LENT));
        if (openLoanCount != null && openLoanCount > 0) {
            throw new IllegalArgumentException("文物仍有借出未归还，不能登记借展协议: " + relic.getRelicNo());
        }

        LocalDateTime now = LocalDateTime.now();
        LoanAgreement agreement = new LoanAgreement();
        agreement.setAgreementNo(req.getAgreementNo());
        agreement.setRelicId(req.getRelicId());
        agreement.setPurpose(req.getPurpose());
        agreement.setStatus(AGR_DRAFT);
        agreementMapper.insert(agreement);

        LoanAgreementVersion v1 = new LoanAgreementVersion();
        v1.setAgreementId(agreement.getId());
        v1.setVersionNo(1);
        v1.setBorrower(req.getBorrower());
        v1.setInsuranceAmount(req.getInsuranceAmount());
        v1.setTransportRoute(req.getTransportRoute());
        v1.setReturnDate(req.getReturnDate());
        v1.setTerms(req.getTerms());
        v1.setStatus(AGR_VERSION_DRAFT);
        v1.setChangeReason("初始登记");
        versionMapper.insert(v1);

        agreement.setCurrentVersionId(v1.getId());
        agreementMapper.updateById(agreement);

        copyItemsIntoVersion(v1.getId(), req.getItems());

        ledgerService.append(req.getRelicId(), null, null,
                ACTION_LOAN_AGR_REGISTER, req.getAgreementNo(),
                "借展协议登记（V1 待签），借展方：" + req.getBorrower()
                        + "，保险金额：" + req.getInsuranceAmount()
                        + "，运输路线：" + req.getTransportRoute()
                        + "，约定归还日期：" + req.getReturnDate()
                        + "，清单 " + req.getItems().size() + " 项", now);
        return agreement;
    }

    /**
     * 协议签署：仅当前 DRAFT 版本可签；签署后版本生效，旧 SIGNED 版本置 SUPERSEDED。
     * 已签协议没有任何修改入口，变更只能走 amend 另出新版。
     */
    @Transactional
    public LoanAgreementVersion sign(Long agreementId, AgreementSignRequest req) {
        LoanAgreement agreement = requireAgreementLocked(agreementId);
        if (AGR_RETURNED.equals(agreement.getStatus()) || AGR_CANCELLED.equals(agreement.getStatus())) {
            throw new IllegalArgumentException("协议已闭环，不能再签署: " + agreement.getAgreementNo());
        }
        LoanAgreementVersion current = requireVersion(agreement.getCurrentVersionId());
        if (!AGR_VERSION_DRAFT.equals(current.getStatus())) {
            throw new IllegalArgumentException("当前协议版本已签署，已签协议不能覆盖，变更请另出新版本: "
                    + agreement.getAgreementNo());
        }

        LocalDateTime now = req.getSignedAt() == null ? LocalDateTime.now() : req.getSignedAt();
        // 新版签署：同协议历史 SIGNED 版本一律置 SUPERSEDED（内容不改写）
        List<LoanAgreementVersion> signedVersions = versionMapper.selectList(
                new LambdaQueryWrapper<LoanAgreementVersion>()
                        .eq(LoanAgreementVersion::getAgreementId, agreementId)
                        .eq(LoanAgreementVersion::getStatus, AGR_VERSION_SIGNED));
        for (LoanAgreementVersion old : signedVersions) {
            old.setStatus(AGR_VERSION_SUPERSEDED);
            versionMapper.updateById(old);
        }

        current.setStatus(AGR_VERSION_SIGNED);
        current.setSignedBy(req.getSignedBy());
        current.setSignedAt(now);
        versionMapper.updateById(current);

        if (AGR_DRAFT.equals(agreement.getStatus())) {
            agreement.setStatus(AGR_SIGNED);
            agreementMapper.updateById(agreement);
        }

        // museum-15：签约即固化保险估值快照（不可变）。借展期间一律沿用本快照，
        // 即使随后估值修订、出具批单或到期提醒，在借口径都不变化。
        // 快照读取不修改估值，属签约事实固化，不受估值变更收紧闸门影响。
        valuationService.snapshotOnAgreementSigned(agreement, current, req.getSignedBy(), now);

        ledgerService.append(agreement.getRelicId(), null, null,
                ACTION_LOAN_AGR_SIGN, agreement.getAgreementNo(),
                "借展协议 V" + current.getVersionNo() + " 由 " + req.getSignedBy() + " 签署生效"
                        + (signedVersions.isEmpty() ? ""
                        : "，旧版本 V" + signedVersions.get(0).getVersionNo() + " 置 SUPERSEDED"), now);
        return current;
    }

    /**
     * 协议变更：复制当前版本生成 version_no+1 的 DRAFT；新签署前押运节点暂停。
     */
    @Transactional
    public LoanAgreementVersion amend(Long agreementId, AgreementAmendRequest req) {
        LoanAgreement agreement = requireAgreementLocked(agreementId);
        if (AGR_RETURNED.equals(agreement.getStatus()) || AGR_CANCELLED.equals(agreement.getStatus())) {
            throw new IllegalArgumentException("协议已闭环，不能变更: " + agreement.getAgreementNo());
        }
        LoanAgreementVersion current = requireVersion(agreement.getCurrentVersionId());
        if (AGR_VERSION_DRAFT.equals(current.getStatus())) {
            throw new IllegalArgumentException("存在尚未签署的新版本，请先完成签署: "
                    + agreement.getAgreementNo() + " V" + current.getVersionNo());
        }

        Integer maxNo = versionMapper.selectList(new LambdaQueryWrapper<LoanAgreementVersion>()
                        .eq(LoanAgreementVersion::getAgreementId, agreementId)
                        .orderByDesc(LoanAgreementVersion::getVersionNo)
                        .last("LIMIT 1"))
                .stream().findFirst().map(LoanAgreementVersion::getVersionNo).orElse(0);

        LoanAgreementVersion next = new LoanAgreementVersion();
        next.setAgreementId(agreementId);
        next.setVersionNo(maxNo + 1);
        next.setBorrower(req.getBorrower() != null ? req.getBorrower() : current.getBorrower());
        next.setInsuranceAmount(req.getInsuranceAmount() != null
                ? req.getInsuranceAmount() : current.getInsuranceAmount());
        next.setTransportRoute(req.getTransportRoute() != null
                ? req.getTransportRoute() : current.getTransportRoute());
        next.setReturnDate(req.getReturnDate() != null ? req.getReturnDate() : current.getReturnDate());
        next.setTerms(req.getTerms() != null ? req.getTerms() : current.getTerms());
        next.setStatus(AGR_VERSION_DRAFT);
        next.setChangeReason(req.getChangeReason());
        versionMapper.insert(next);

        if (req.getItems() != null && !req.getItems().isEmpty()) {
            copyItemsIntoVersion(next.getId(), req.getItems());
        } else {
            // 清单未随变更提交：原样复制上一版本清单（新行挂新版本，旧快照保留）
            List<LoanManifestLine> oldLines = listManifest(current.getId());
            List<AgreementManifestItem> copies = new ArrayList<>();
            for (LoanManifestLine line : oldLines) {
                AgreementManifestItem item = new AgreementManifestItem();
                item.setItemNo(line.getItemNo());
                item.setItemName(line.getItemName());
                item.setQty(line.getQty());
                item.setOutCondition(line.getOutCondition());
                copies.add(item);
            }
            copyItemsIntoVersion(next.getId(), copies);
        }

        agreement.setCurrentVersionId(next.getId());
        agreementMapper.updateById(agreement);

        ledgerService.append(agreement.getRelicId(), null, null,
                ACTION_LOAN_AGR_AMEND, agreement.getAgreementNo(),
                "借展协议变更，生成 V" + next.getVersionNo() + "（待签），变更原因："
                        + req.getChangeReason() + "；签署前押运节点暂停", LocalDateTime.now());
        return next;
    }

    // ------------------------------------------------------------------
    // 押运节点
    // ------------------------------------------------------------------

    /**
     * 押运节点登记：必须按 封箱->交接->到馆->开箱 顺序；
     * 上一节点异常未解除时暂停；开箱必须两名不同见证人。
     * 首节点封箱后置主单 IN_TRANSIT 并写 t_relic_loan(LENT) 贯通记录。
     */
    @Transactional
    public EscortNode registerNode(Long agreementId, EscortNodeRequest req) {
        LoanAgreement agreement = requireAgreementLocked(agreementId);
        if (!AGR_SIGNED.equals(agreement.getStatus())
                && !AGR_IN_TRANSIT.equals(agreement.getStatus())) {
            throw new IllegalArgumentException("协议未签署或已闭环，不能登记押运节点: "
                    + agreement.getAgreementNo() + "（" + agreement.getStatus() + "）");
        }
        LoanAgreementVersion current = requireVersion(agreement.getCurrentVersionId());
        if (!AGR_VERSION_SIGNED.equals(current.getStatus())) {
            throw new IllegalArgumentException("协议新版本 V" + current.getVersionNo()
                    + " 尚未签署，押运节点暂停: " + agreement.getAgreementNo());
        }

        String nodeType = req.getNodeType();
        int seq = NODE_ORDER.indexOf(nodeType);
        if (seq < 0) {
            throw new IllegalArgumentException("未知押运节点类型: " + nodeType
                    + "，只能为 SEAL/HANDOVER/ARRIVAL/OPEN_BOX");
        }
        seq += 1;

        // 本节点已登记
        if (nodeMapper.selectCount(new LambdaQueryWrapper<EscortNode>()
                .eq(EscortNode::getAgreementId, agreementId)
                .eq(EscortNode::getNodeType, nodeType)) > 0) {
            throw new IllegalArgumentException("押运节点已登记，不能重复登记: " + nodeType);
        }
        // 顺序与暂停校验：上一节点必须存在，且异常必须已解除
        if (seq > 1) {
            String prevType = NODE_ORDER.get(seq - 2);
            EscortNode prev = nodeMapper.selectOne(new LambdaQueryWrapper<EscortNode>()
                    .eq(EscortNode::getAgreementId, agreementId)
                    .eq(EscortNode::getNodeType, prevType));
            if (prev == null) {
                throw new IllegalArgumentException("押运节点不能跳序登记：须先完成 " + prevType);
            }
            if (prev.getAbnormal() != null && prev.getAbnormal() == 1 && prev.getResolvedBy() == null) {
                throw new IllegalArgumentException("上一节点 " + prevType + " 存在未解除异常，"
                        + "后续操作已暂停（异常原因：" + prev.getAnomalyReason() + "）");
            }
        }

        boolean abnormal = Boolean.TRUE.equals(req.getAbnormal());
        if (abnormal && (req.getAnomalyReason() == null || req.getAnomalyReason().trim().isEmpty())) {
            throw new IllegalArgumentException("登记节点异常必须填写异常原因: " + nodeType);
        }
        // 开箱双人见证：两人必填且不得为同一人
        if (NODE_OPEN_BOX.equals(nodeType)) {
            if (req.getWitnessA() == null || req.getWitnessA().trim().isEmpty()
                    || req.getWitnessB() == null || req.getWitnessB().trim().isEmpty()) {
                throw new IllegalArgumentException("开箱必须由两名见证人共同在场，见证人均不能为空");
            }
            if (req.getWitnessA().equals(req.getWitnessB())) {
                throw new IllegalArgumentException("开箱两名见证人不能为同一人");
            }
        }

        LocalDateTime now = req.getRecordedAt() == null ? LocalDateTime.now() : req.getRecordedAt();
        EscortNode node = new EscortNode();
        node.setAgreementId(agreementId);
        node.setAgreementVersionId(current.getId());
        node.setNodeType(nodeType);
        node.setSeqNo(seq);
        node.setHandler(req.getHandler());
        node.setRecordedAt(now);
        node.setRemark(req.getRemark());
        node.setAbnormal(abnormal ? 1 : 0);
        node.setAnomalyReason(abnormal ? req.getAnomalyReason() : null);
        node.setWitnessA(NODE_OPEN_BOX.equals(nodeType) ? req.getWitnessA() : null);
        node.setWitnessB(NODE_OPEN_BOX.equals(nodeType) ? req.getWitnessB() : null);
        nodeMapper.insert(node);

        // 封箱：协议进入押运中，并写 museum-1 借出贯通记录（复用跨业务占用校验）
        if (NODE_SEAL.equals(nodeType)) {
            agreement.setStatus(AGR_IN_TRANSIT);
            agreementMapper.updateById(agreement);
            createLinkingLoan(agreement, current, now);
        }

        ledgerService.append(agreement.getRelicId(), null, null,
                ACTION_ESCORT_NODE, agreement.getAgreementNo(),
                "押运节点 " + nodeType + "（第" + seq + "步）登记，处理人：" + req.getHandler()
                        + (NODE_OPEN_BOX.equals(nodeType)
                        ? "，开箱见证人：" + req.getWitnessA() + "、" + req.getWitnessB() : "")
                        + (req.getRemark() == null ? "" : "，备注：" + req.getRemark()), now);
        if (abnormal) {
            ledgerService.append(agreement.getRelicId(), null, null,
                    ACTION_ESCORT_ANOMALY, agreement.getAgreementNo(),
                    "押运节点 " + nodeType + " 登记异常，后续操作暂停；处理人："
                            + req.getHandler() + "，异常原因：" + req.getAnomalyReason(), now);
        }
        return node;
    }

    /**
     * 押运异常解除：解除后后续节点方可继续。
     */
    @Transactional
    public EscortNode resolveAnomaly(Long nodeId, AnomalyResolveRequest req) {
        EscortNode node = requireNode(nodeId);
        if (node.getAbnormal() == null || node.getAbnormal() != 1) {
            throw new IllegalArgumentException("该押运节点未登记异常，无需解除: " + node.getNodeType());
        }
        if (node.getResolvedBy() != null) {
            throw new IllegalArgumentException("该节点异常已解除，不能重复解除: " + node.getNodeType());
        }
        LoanAgreement agreement = requireAgreement(node.getAgreementId());

        LocalDateTime now = req.getResolvedAt() == null ? LocalDateTime.now() : req.getResolvedAt();
        node.setResolvedBy(req.getResolvedBy());
        node.setResolvedAt(now);
        node.setResolveRemark(req.getRemark());
        nodeMapper.updateById(node);

        ledgerService.append(agreement.getRelicId(), null, null,
                ACTION_ESCORT_RESUME, agreement.getAgreementNo(),
                "押运节点 " + node.getNodeType() + " 异常解除，后续操作恢复；解除人："
                        + req.getResolvedBy() + "，解除说明：" + req.getRemark(), now);
        return node;
    }

    // ------------------------------------------------------------------
    // 归还验收
    // ------------------------------------------------------------------

    /**
     * 归还验收：开箱完成且异常全部解除后，按封箱出库时签署版本的原借展清单
     * 逐项比对，漏项/多项/重复均拒绝；差异逐行保留，闭环协议并同步归还 t_relic_loan。
     *
     * 并发与回执口径（状态转换的唯一入口）：
     * 1) 先对协议行加排他行锁，同一协议的并发验收在此串行；
     * 2) 锁内回读已完成验收：同一验收单号的重复回执命中原验收原样返回（幂等回放，
     *    不重复执行、不二次闭环），不同单号拒绝重复验收；
     * 3) 验收闭环 = 协议 RETURNED + 贯通借出单 RETURNED，同一事务原子提交，
     *    外借清单（t_relic_loan LENT 口径）随即不再包含该文物；
     * 4) 唯一约束（uk_loan_return_check_agr/no）兜底：被并发击穿时转成业务拒绝，
     *    绝不冒出与文物/项目编号无关的通用编号冲突。
     */
    @Transactional
    public LoanReturnCheck returnCheck(Long agreementId, ReturnCheckRequest req) {
        // 协议行锁 + 锁内重读：并发验收串行化，重复回执/落败请求看到最终状态
        LoanAgreement agreement = requireAgreementLocked(agreementId);

        LoanReturnCheck existing = checkOfAgreement(agreementId);
        if (existing != null) {
            if (existing.getCheckNo().equals(req.getCheckNo())) {
                // 重复回执：同一验收单号命中同一验收，原样回放，不重复闭环
                return existing;
            }
            throw new IllegalArgumentException("该协议已完成归还验收，不能重复验收: "
                    + agreement.getAgreementNo());
        }
        if (!AGR_SIGNED.equals(agreement.getStatus())
                && !AGR_IN_TRANSIT.equals(agreement.getStatus())) {
            throw new IllegalArgumentException("协议状态不允许归还验收: "
                    + agreement.getAgreementNo() + "（" + agreement.getStatus() + "）");
        }
        if (checkNoExists(req.getCheckNo())) {
            throw new IllegalArgumentException("验收单号已存在: " + req.getCheckNo());
        }

        // 四节点须齐备，且不存在未解除异常
        List<EscortNode> nodes = listNodes(agreementId);
        Set<String> nodeTypes = new HashSet<>();
        for (EscortNode node : nodes) {
            nodeTypes.add(node.getNodeType());
            if (node.getAbnormal() != null && node.getAbnormal() == 1 && node.getResolvedBy() == null) {
                throw new IllegalArgumentException("押运节点 " + node.getNodeType()
                        + " 存在未解除异常，不能归还验收");
            }
        }
        if (!nodeTypes.containsAll(NODE_ORDER)) {
            throw new IllegalArgumentException("封箱/交接/到馆/开箱四个押运节点未全部完成，不能归还验收");
        }

        // 原借展清单 = 封箱出库时签署版本的清单快照
        EscortNode seal = nodes.stream()
                .filter(n -> NODE_SEAL.equals(n.getNodeType())).findFirst().orElse(null);
        Long outboundVersionId = seal == null ? agreement.getCurrentVersionId()
                : seal.getAgreementVersionId();
        List<LoanManifestLine> manifest = listManifest(outboundVersionId);

        Map<Long, LoanManifestLine> manifestById = new LinkedHashMap<>();
        for (LoanManifestLine line : manifest) {
            manifestById.put(line.getId(), line);
        }
        // 请求行：不允许重复、不允许引用不存在或属于其他版本的清单行
        Set<Long> submittedIds = new HashSet<>();
        for (ReturnCheckItemDto item : req.getItems()) {
            if (!submittedIds.add(item.getManifestLineId())) {
                throw new IllegalArgumentException("验收比对重复同一清单行: id=" + item.getManifestLineId());
            }
            if (!manifestById.containsKey(item.getManifestLineId())) {
                throw new IllegalArgumentException("验收行不属于原借展清单或条目不存在: id="
                        + item.getManifestLineId());
            }
            if (!LINE_INTACT.equals(item.getResult()) && !LINE_DAMAGED.equals(item.getResult())
                    && !LINE_MISSING.equals(item.getResult())) {
                throw new IllegalArgumentException("比对结果只能为 INTACT/DAMAGED/MISSING");
            }
            if (!LINE_INTACT.equals(item.getResult())
                    && (item.getDiffNote() == null || item.getDiffNote().trim().isEmpty())) {
                LoanManifestLine line = manifestById.get(item.getManifestLineId());
                throw new IllegalArgumentException("条目「" + line.getItemName()
                        + "」比对为 " + item.getResult() + "，必须填写差异说明");
            }
        }
        if (submittedIds.size() != manifest.size()) {
            throw new IllegalArgumentException("归还验收必须与原借展清单逐项比对：清单共 "
                    + manifest.size() + " 项，实际提交 " + submittedIds.size() + " 项，不允许漏项或多项");
        }

        LocalDateTime now = req.getCheckedAt() == null ? LocalDateTime.now() : req.getCheckedAt();
        boolean withDiff = req.getItems().stream()
                .anyMatch(i -> !LINE_INTACT.equals(i.getResult()));
        LoanReturnCheck check = new LoanReturnCheck();
        check.setAgreementId(agreementId);
        check.setCheckNo(req.getCheckNo());
        check.setCheckedBy(req.getCheckedBy());
        check.setCheckedAt(now);
        check.setConclusion(withDiff ? CHECK_WITH_DIFF : CHECK_ALL_MATCH);
        check.setSummary(req.getSummary());
        try {
            checkMapper.insert(check);
        } catch (DuplicateKeyException dup) {
            // 唯一约束兜底（行锁之外的极端竞态）：同协议同单号按重复回执幂等回放；
            // 同协议不同单号为重复验收、跨协议同单号为单号冲突，按业务口径拒绝。
            // H2 约束冲突只回滚当前语句，事务可继续读；拒绝路径随异常整体回滚。
            LoanReturnCheck winner = checkOfAgreement(agreementId);
            if (winner != null && winner.getCheckNo().equals(req.getCheckNo())) {
                return winner;
            }
            throw duplicateCheck(agreementId, req.getCheckNo());
        }

        for (ReturnCheckItemDto item : req.getItems()) {
            LoanManifestLine line = manifestById.get(item.getManifestLineId());
            LoanReturnCheckLine saved = new LoanReturnCheckLine();
            saved.setCheckId(check.getId());
            saved.setManifestLineId(line.getId());
            saved.setItemNo(line.getItemNo());
            saved.setItemName(line.getItemName());
            saved.setQty(line.getQty());
            saved.setResult(item.getResult());
            saved.setDiffNote(LINE_INTACT.equals(item.getResult()) ? null : item.getDiffNote());
            checkLineMapper.insert(saved);
        }

        agreement.setStatus(AGR_RETURNED);
        agreementMapper.updateById(agreement);
        closeLinkingLoan(agreement, req.getCheckedBy(), check.getConclusion(), now);

        long damaged = req.getItems().stream().filter(i -> LINE_DAMAGED.equals(i.getResult())).count();
        long missing = req.getItems().stream().filter(i -> LINE_MISSING.equals(i.getResult())).count();
        ledgerService.append(agreement.getRelicId(), null, null,
                ACTION_LOAN_RETURN_CHECK, agreement.getAgreementNo(),
                "归还验收完成（" + check.getCheckNo() + "），验收人：" + req.getCheckedBy()
                        + "，结论：" + (withDiff ? "存在差异" : "全部相符")
                        + "，损伤 " + damaged + " 项、缺失 " + missing + " 项，差异已逐项保留", now);
        return check;
    }

    /**
     * 归还验收差异补录：验收闭环后复查发现的差异只追加登记，不改写原比对行。
     * 口径：
     * 1) 仅协议已归还验收（RETURNED 且验收记录存在）可补录；
     * 2) 补录目标必须是验收时逐项比对过的清单行，类型只能 DAMAGED/MISSING，差异说明必填；
     * 3) 完全相同的补录（同清单行、同类型、同说明）拒绝重复登记；
     * 4) 首条补录把验收结论 ALL_MATCH 更正为 WITH_DIFF；
     *    补录只追加差异——协议保持 RETURNED、贯通借出单保持 RETURNED，
     *    绝不重新打开已完成归还。
     */
    @Transactional
    public LoanReturnCheckSupp supplementReturnDiff(Long agreementId, ReturnCheckSuppRequest req) {
        // 协议行锁 + 锁内重读：补录与验收/并发补录串行，结论更正只在首条补录发生一次
        LoanAgreement agreement = requireAgreementLocked(agreementId);
        if (!AGR_RETURNED.equals(agreement.getStatus())) {
            throw new IllegalArgumentException("协议未完成归还验收，不能补录差异: "
                    + agreement.getAgreementNo() + "（" + agreement.getStatus() + "）");
        }
        LoanReturnCheck check = checkOfAgreement(agreementId);
        if (check == null) {
            throw new IllegalArgumentException("协议缺少归还验收记录，不能补录差异: "
                    + agreement.getAgreementNo());
        }
        // 补录目标必须是验收时逐项比对过的清单行
        LoanReturnCheckLine target = checkLineMapper.selectList(new LambdaQueryWrapper<LoanReturnCheckLine>()
                        .eq(LoanReturnCheckLine::getCheckId, check.getId()))
                .stream()
                .filter(l -> l.getManifestLineId().equals(req.getManifestLineId()))
                .findFirst().orElse(null);
        if (target == null) {
            throw new IllegalArgumentException("补录行不属于原验收比对清单: id=" + req.getManifestLineId());
        }
        if (!LINE_DAMAGED.equals(req.getResult()) && !LINE_MISSING.equals(req.getResult())) {
            throw new IllegalArgumentException("补录差异类型只能为 DAMAGED/MISSING");
        }
        if (req.getDiffNote() == null || req.getDiffNote().trim().isEmpty()) {
            throw new IllegalArgumentException("补录差异必须填写差异说明");
        }
        Long dup = checkSuppMapper.selectCount(new LambdaQueryWrapper<LoanReturnCheckSupp>()
                .eq(LoanReturnCheckSupp::getCheckId, check.getId())
                .eq(LoanReturnCheckSupp::getManifestLineId, req.getManifestLineId())
                .eq(LoanReturnCheckSupp::getResult, req.getResult())
                .eq(LoanReturnCheckSupp::getDiffNote, req.getDiffNote().trim()));
        if (dup != null && dup > 0) {
            throw new IllegalArgumentException("该差异已补录登记，不能重复补录: " + target.getItemNo());
        }

        LocalDateTime now = req.getSupplementedAt() == null ? LocalDateTime.now() : req.getSupplementedAt();
        LoanReturnCheckSupp supp = new LoanReturnCheckSupp();
        supp.setCheckId(check.getId());
        supp.setManifestLineId(target.getManifestLineId());
        supp.setItemNo(target.getItemNo());
        supp.setItemName(target.getItemName());
        supp.setQty(target.getQty());
        supp.setResult(req.getResult());
        supp.setDiffNote(req.getDiffNote().trim());
        supp.setSupplementedBy(req.getSupplementedBy().trim());
        supp.setSupplementedAt(now);
        checkSuppMapper.insert(supp);

        // 首条补录把验收结论更正为 WITH_DIFF（原比对行不改写）
        if (CHECK_ALL_MATCH.equals(check.getConclusion())) {
            check.setConclusion(CHECK_WITH_DIFF);
            checkMapper.updateById(check);
        }

        ledgerService.append(agreement.getRelicId(), null, null,
                ACTION_LOAN_RETURN_CHECK_SUPP, agreement.getAgreementNo(),
                "归还验收差异补录（" + check.getCheckNo() + "）：条目「" + target.getItemName()
                        + "」" + req.getResult() + "，差异说明：" + supp.getDiffNote()
                        + "，补录人：" + supp.getSupplementedBy()
                        + "；协议保持已归还，不重新打开", now);
        return supp;
    }

    /** 验收差异补录列表（随验收记录只追加） */
    public List<LoanReturnCheckSupp> listCheckSupplements(Long checkId) {
        return checkSuppMapper.selectList(new LambdaQueryWrapper<LoanReturnCheckSupp>()
                .eq(LoanReturnCheckSupp::getCheckId, checkId)
                .orderByAsc(LoanReturnCheckSupp::getId));
    }

    // ------------------------------------------------------------------
    // 查询与修复侧反查
    // ------------------------------------------------------------------

    public LoanAgreement requireAgreement(Long id) {
        LoanAgreement agreement = agreementMapper.selectById(id);
        if (agreement == null) {
            throw new IllegalArgumentException("借展协议不存在: id=" + id);
        }
        return agreement;
    }

    /**
     * 协议状态变更的统一入口：先加协议行锁，再在锁内重读。
     * 签署/变更/节点/验收/补录全部经此拿到已提交的最新状态做校验与写入，
     * 并发下不会用过期实体把已闭环协议写回在途（已完成归还不能被重新打开）。
     */
    private LoanAgreement requireAgreementLocked(Long id) {
        requireAgreement(id);
        agreementMapper.lockRow(id);
        return agreementMapper.selectById(id);
    }

    public List<LoanAgreement> list(Long relicId, String status) {
        LambdaQueryWrapper<LoanAgreement> wrapper = new LambdaQueryWrapper<LoanAgreement>()
                .orderByDesc(LoanAgreement::getId);
        if (relicId != null) {
            wrapper.eq(LoanAgreement::getRelicId, relicId);
        }
        if (status != null && !status.isEmpty()) {
            wrapper.eq(LoanAgreement::getStatus, status);
        }
        return agreementMapper.selectList(wrapper);
    }

    /** 协议详情：主单 + 全部版本（各带清单）+ 押运节点 + 归还验收（带逐行比对） */
    public Map<String, Object> detail(Long agreementId) {
        LoanAgreement agreement = requireAgreement(agreementId);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("agreement", agreement);

        List<Map<String, Object>> versions = new ArrayList<>();
        for (LoanAgreementVersion version : listVersions(agreementId)) {
            Map<String, Object> bundle = new LinkedHashMap<>();
            bundle.put("version", version);
            bundle.put("items", listManifest(version.getId()));
            versions.add(bundle);
        }
        detail.put("versions", versions);
        detail.put("escortNodes", listNodes(agreementId));

        LoanReturnCheck check = checkMapper.selectOne(new LambdaQueryWrapper<LoanReturnCheck>()
                .eq(LoanReturnCheck::getAgreementId, agreementId));
        if (check != null) {
            Map<String, Object> checkBundle = new LinkedHashMap<>();
            checkBundle.put("check", check);
            checkBundle.put("lines", checkLineMapper.selectList(new LambdaQueryWrapper<LoanReturnCheckLine>()
                    .eq(LoanReturnCheckLine::getCheckId, check.getId())
                    .orderByAsc(LoanReturnCheckLine::getId)));
            checkBundle.put("supplements", listCheckSupplements(check.getId()));
            detail.put("returnCheck", checkBundle);
        } else {
            detail.put("returnCheck", null);
        }
        return detail;
    }

    /**
     * 修复侧反查：由修复项目 -> 文物 -> 借展协议原单，
     * 定位原单号、各押运节点处理人/异常原因/解除人，以及归还验收差异。
     */
    public Map<String, Object> traceByProject(Long projectId) {
        RepairProject project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new IllegalArgumentException("修复项目不存在: id=" + projectId);
        }
        Relic relic = relicService.requireRelic(project.getRelicId());

        List<LoanAgreement> agreements = agreementMapper.selectList(new LambdaQueryWrapper<LoanAgreement>()
                .eq(LoanAgreement::getRelicId, relic.getId())
                .orderByDesc(LoanAgreement::getId));
        List<Map<String, Object>> traces = new ArrayList<>();
        for (LoanAgreement agreement : agreements) {
            Map<String, Object> trace = new LinkedHashMap<>();
            trace.put("agreementId", agreement.getId());
            trace.put("agreementNo", agreement.getAgreementNo());
            trace.put("status", agreement.getStatus());
            LoanAgreementVersion current = agreement.getCurrentVersionId() == null ? null
                    : versionMapper.selectById(agreement.getCurrentVersionId());
            trace.put("currentVersionNo", current == null ? null : current.getVersionNo());
            if (current != null) {
                trace.put("borrower", current.getBorrower());
                trace.put("insuranceAmount", current.getInsuranceAmount());
                trace.put("transportRoute", current.getTransportRoute());
                trace.put("returnDate", current.getReturnDate());
            }
            trace.put("escortNodes", listNodes(agreement.getId()));
            LoanReturnCheck check = checkMapper.selectOne(new LambdaQueryWrapper<LoanReturnCheck>()
                    .eq(LoanReturnCheck::getAgreementId, agreement.getId()));
            if (check != null) {
                Map<String, Object> checkBundle = new LinkedHashMap<>();
                checkBundle.put("checkNo", check.getCheckNo());
                checkBundle.put("conclusion", check.getConclusion());
                checkBundle.put("checkedBy", check.getCheckedBy());
                checkBundle.put("lines", checkLineMapper.selectList(
                        new LambdaQueryWrapper<LoanReturnCheckLine>()
                                .eq(LoanReturnCheckLine::getCheckId, check.getId())
                                .ne(LoanReturnCheckLine::getResult, LINE_INTACT)));
                checkBundle.put("supplements", listCheckSupplements(check.getId()));
                trace.put("returnCheck", checkBundle);
            } else {
                trace.put("returnCheck", null);
            }
            traces.add(trace);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectId", project.getId());
        result.put("projectNo", project.getProjectNo());
        result.put("relicId", relic.getId());
        result.put("relicNo", relic.getRelicNo());
        result.put("agreements", traces);
        return result;
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    private void createLinkingLoan(LoanAgreement agreement, LoanAgreementVersion version,
                                   LocalDateTime lentAt) {
        // 同一协议封箱只发生一次，uk_loan_no 兜底；沿用 LoanService 的占用口径
        RelicLoan loan = new RelicLoan();
        loan.setLoanNo(agreement.getAgreementNo());
        loan.setRelicId(agreement.getRelicId());
        loan.setBorrower(version.getBorrower());
        loan.setPurpose(agreement.getPurpose());
        loan.setLentAt(lentAt);
        loan.setExpectedReturnAt(version.getReturnDate() == null
                ? null : version.getReturnDate().atStartOfDay());
        loan.setStatus(LOAN_LENT);
        relicLoanMapper.insert(loan);
    }

    private void closeLinkingLoan(LoanAgreement agreement, String checkedBy,
                                  String conclusion, LocalDateTime checkedAt) {
        RelicLoan loan = relicLoanMapper.selectOne(new LambdaQueryWrapper<RelicLoan>()
                .eq(RelicLoan::getLoanNo, agreement.getAgreementNo())
                .eq(RelicLoan::getStatus, LOAN_LENT)
                .last("LIMIT 1"));
        if (loan == null) {
            return;
        }
        loan.setStatus(LOAN_RETURNED);
        loan.setReturnedAt(checkedAt);
        loan.setReceiver(checkedBy);
        loan.setReturnCondition(CHECK_WITH_DIFF.equals(conclusion) ? "归还验收存在差异" : "归还验收全部相符");
        relicLoanMapper.updateById(loan);
    }

    private void copyItemsIntoVersion(Long versionId, List<AgreementManifestItem> items) {
        int lineNo = 1;
        Set<String> itemNos = new HashSet<>();
        for (AgreementManifestItem item : items) {
            if (!itemNos.add(item.getItemNo())) {
                throw new IllegalArgumentException("同一协议版本清单条目编号不能重复: " + item.getItemNo());
            }
            LoanManifestLine line = new LoanManifestLine();
            line.setAgreementVersionId(versionId);
            line.setLineNo(lineNo++);
            line.setItemNo(item.getItemNo());
            line.setItemName(item.getItemName());
            line.setQty(item.getQty());
            line.setOutCondition(item.getOutCondition());
            manifestMapper.insert(line);
        }
    }

    private boolean checkNoExists(String checkNo) {
        return checkMapper.selectCount(new LambdaQueryWrapper<LoanReturnCheck>()
                .eq(LoanReturnCheck::getCheckNo, checkNo)) > 0;
    }

    /** 协议的归还验收记录（一协议一条，无则 null） */
    private LoanReturnCheck checkOfAgreement(Long agreementId) {
        return checkMapper.selectOne(new LambdaQueryWrapper<LoanReturnCheck>()
                .eq(LoanReturnCheck::getAgreementId, agreementId)
                .last("LIMIT 1"));
    }

    /** 唯一约束被击穿时按业务口径归类：同协议为重复验收，跨协议为验收单号冲突 */
    private IllegalArgumentException duplicateCheck(Long agreementId, String checkNo) {
        if (checkOfAgreement(agreementId) != null) {
            return new IllegalArgumentException("该协议已完成归还验收，不能重复验收");
        }
        return new IllegalArgumentException("验收单号已存在: " + checkNo);
    }

    private LoanAgreementVersion requireVersion(Long versionId) {
        if (versionId == null) {
            throw new IllegalArgumentException("协议当前版本缺失");
        }
        LoanAgreementVersion version = versionMapper.selectById(versionId);
        if (version == null) {
            throw new IllegalArgumentException("协议版本不存在: id=" + versionId);
        }
        return version;
    }

    private EscortNode requireNode(Long nodeId) {
        EscortNode node = nodeMapper.selectById(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("押运节点不存在: id=" + nodeId);
        }
        return node;
    }

    private List<LoanAgreementVersion> listVersions(Long agreementId) {
        return versionMapper.selectList(new LambdaQueryWrapper<LoanAgreementVersion>()
                .eq(LoanAgreementVersion::getAgreementId, agreementId)
                .orderByAsc(LoanAgreementVersion::getVersionNo));
    }

    private List<LoanManifestLine> listManifest(Long versionId) {
        if (versionId == null) {
            return Collections.emptyList();
        }
        return manifestMapper.selectList(new LambdaQueryWrapper<LoanManifestLine>()
                .eq(LoanManifestLine::getAgreementVersionId, versionId)
                .orderByAsc(LoanManifestLine::getLineNo));
    }

    private List<EscortNode> listNodes(Long agreementId) {
        return nodeMapper.selectList(new LambdaQueryWrapper<EscortNode>()
                .eq(EscortNode::getAgreementId, agreementId)
                .orderByAsc(EscortNode::getSeqNo));
    }
}
