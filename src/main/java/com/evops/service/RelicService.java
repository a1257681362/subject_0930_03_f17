package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.constant.MuseumConstants;
import com.evops.dto.RelicCreateRequest;
import com.evops.dto.RelicQuery;
import com.evops.entity.LoanAgreement;
import com.evops.entity.Relic;
import com.evops.entity.RelicLoan;
import com.evops.entity.RepairProject;
import com.evops.mapper.LoanAgreementMapper;
import com.evops.mapper.RelicLoanMapper;
import com.evops.mapper.RelicMapper;
import com.evops.mapper.RepairProjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static com.evops.constant.MuseumConstants.*;

/**
 * 文物档案：建档、查询、撤销。
 * 撤销是状态流转（ACTIVE -> REVOKED），记录永久保留；编号唯一性覆盖已撤销记录。
 */
@Service
public class RelicService {

    private static final List<String> GRADES =
            Arrays.asList(GRADE_ONE, GRADE_TWO, GRADE_THREE);

    private final RelicMapper relicMapper;
    private final RelicLoanMapper loanMapper;
    private final LoanAgreementMapper agreementMapper;
    private final RepairProjectMapper projectMapper;
    private final LedgerService ledgerService;

    public RelicService(RelicMapper relicMapper,
                        RelicLoanMapper loanMapper,
                        LoanAgreementMapper agreementMapper,
                        RepairProjectMapper projectMapper,
                        LedgerService ledgerService) {
        this.relicMapper = relicMapper;
        this.loanMapper = loanMapper;
        this.agreementMapper = agreementMapper;
        this.projectMapper = projectMapper;
        this.ledgerService = ledgerService;
    }

    public Relic create(RelicCreateRequest req) {
        if (!GRADES.contains(req.getPreservationGrade())) {
            throw new IllegalArgumentException("保存等级只能为 ONE/TWO/THREE");
        }
        if (findByNo(req.getRelicNo()) != null) {
            throw new IllegalArgumentException("文物编号已存在: " + req.getRelicNo());
        }
        Relic relic = new Relic();
        relic.setRelicNo(req.getRelicNo());
        relic.setName(req.getName());
        relic.setCategory(req.getCategory());
        relic.setMaterial(req.getMaterial());
        relic.setEra(req.getEra());
        relic.setSource(req.getSource());
        relic.setPreservationGrade(req.getPreservationGrade());
        relic.setCampusCode(req.getCampusCode() == null || req.getCampusCode().trim().isEmpty()
                ? "MAIN" : req.getCampusCode().trim());
        relic.setDescription(req.getDescription());
        relic.setStatus(ACTIVE);
        relicMapper.insert(relic);
        return relic;
    }

    public Relic findByNo(String relicNo) {
        return relicMapper.selectOne(new LambdaQueryWrapper<Relic>()
                .eq(Relic::getRelicNo, relicNo));
    }

    /** 按主键取档案，不存在即报错 */
    public Relic requireRelic(Long id) {
        Relic relic = relicMapper.selectById(id);
        if (relic == null) {
            throw new IllegalArgumentException("文物不存在: id=" + id);
        }
        return relic;
    }

    /** 取在档（未撤销）文物 */
    public Relic requireActiveRelic(Long id) {
        Relic relic = requireRelic(id);
        if (MuseumConstants.REVOKED.equals(relic.getStatus())) {
            throw new IllegalArgumentException("文物已撤销，不能操作: " + relic.getRelicNo());
        }
        return relic;
    }

    public List<Relic> list(RelicQuery query) {
        LambdaQueryWrapper<Relic> wrapper = new LambdaQueryWrapper<Relic>()
                .orderByDesc(Relic::getId);
        if (query != null) {
            if (query.getStatus() != null && !query.getStatus().isEmpty()) {
                wrapper.eq(Relic::getStatus, query.getStatus());
            }
            if (query.getKeyword() != null && !query.getKeyword().isEmpty()) {
                wrapper.and(w -> w.like(Relic::getRelicNo, query.getKeyword())
                        .or().like(Relic::getName, query.getKeyword()));
            }
        }
        return relicMapper.selectList(wrapper);
    }

    /**
     * 撤销文物：仅改状态。借出未还或存在未终结修复项目时不允许撤销，
     * 避免业务链路断在半截。
     */
    public Relic revoke(Long id, String reason) {
        Relic relic = requireRelic(id);
        if (REVOKED.equals(relic.getStatus())) {
            throw new IllegalArgumentException("文物已处于撤销状态: " + relic.getRelicNo());
        }
        Long openLoans = loanMapper.selectCount(new LambdaQueryWrapper<RelicLoan>()
                .eq(RelicLoan::getRelicId, id)
                .eq(RelicLoan::getStatus, LOAN_LENT));
        if (openLoans != null && openLoans > 0) {
            throw new IllegalArgumentException("文物尚有借出未归还，不能撤销");
        }
        // museum-6：协议已登记但尚未归还验收（含已签未封箱）也不允许撤销
        Long openAgreements = agreementMapper.selectCount(new LambdaQueryWrapper<LoanAgreement>()
                .eq(LoanAgreement::getRelicId, id)
                .notIn(LoanAgreement::getStatus, AGR_RETURNED, AGR_CANCELLED));
        if (openAgreements != null && openAgreements > 0) {
            throw new IllegalArgumentException("文物存在未闭环借展协议，不能撤销");
        }
        Long openProjects = projectMapper.selectCount(new LambdaQueryWrapper<RepairProject>()
                .eq(RepairProject::getRelicId, id)
                .in(RepairProject::getStatus,
                        PROJECT_PLANNED, PROJECT_REPAIRING, PROJECT_REPAIRED));
        if (openProjects != null && openProjects > 0) {
            throw new IllegalArgumentException("文物存在未验收/未作废的修复项目，不能撤销");
        }
        relic.setStatus(REVOKED);
        relic.setRevokeReason(reason);
        relic.setRevokedAt(LocalDateTime.now());
        relicMapper.updateById(relic);

        ledgerService.append(id, null, null, ACTION_REVOKE, relic.getRelicNo(),
                "文物档案撤销，原因：" + reason, relic.getRevokedAt());
        return relic;
    }
}
