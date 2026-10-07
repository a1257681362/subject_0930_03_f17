package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.config.MuseumCountersignProperties;
import com.evops.dto.PlanSignRequest;
import com.evops.dto.PlanVersionCreateRequest;
import com.evops.entity.Relic;
import com.evops.entity.RepairPlanSign;
import com.evops.entity.RepairPlanVersion;
import com.evops.entity.RepairProject;
import com.evops.mapper.RepairPlanSignMapper;
import com.evops.mapper.RepairPlanVersionMapper;
import com.evops.mapper.RepairProjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static com.evops.constant.MuseumConstants.*;

/**
 * 修复方案会签：
 *   提交方案版本（按文物风险配置会签顺序）-> 三类应签人依次签署
 *   -> 全部同意则 APPROVED；任一人退回则版本 RETURNED 只读，修订只能另开新版本。
 * 签名归属同一口径：签名永远挂在"签署时处于会签中的版本"上，
 * 封存（退回/通过）后的版本不再接受任何签名；缺席专家不能由管理员代签。
 */
@Service
public class RepairPlanService {

    private final RepairPlanVersionMapper versionMapper;
    private final RepairPlanSignMapper signMapper;
    private final RepairProjectMapper projectMapper;
    private final RelicService relicService;
    private final LedgerService ledgerService;
    private final MuseumCountersignProperties countersignProperties;

    public RepairPlanService(RepairPlanVersionMapper versionMapper,
                             RepairPlanSignMapper signMapper,
                             RepairProjectMapper projectMapper,
                             RelicService relicService,
                             LedgerService ledgerService,
                             MuseumCountersignProperties countersignProperties) {
        this.versionMapper = versionMapper;
        this.signMapper = signMapper;
        this.projectMapper = projectMapper;
        this.relicService = relicService;
        this.ledgerService = ledgerService;
        this.countersignProperties = countersignProperties;
    }

    /**
     * 提交新方案版本：仅立项阶段可提交；上一版本被退回后修订只能走这里另开新版本，
     * 旧版本保持只读。风险等级取提交时文物保存等级快照。
     */
    @Transactional
    public RepairPlanVersion createVersion(Long projectId, PlanVersionCreateRequest req) {
        RepairProject project = requireProject(projectId);
        if (!PROJECT_PLANNED.equals(project.getStatus())) {
            throw new IllegalArgumentException("项目已开工或已闭环，不能再提交新方案版本: " + project.getProjectNo());
        }
        Long signing = versionMapper.selectCount(new LambdaQueryWrapper<RepairPlanVersion>()
                .eq(RepairPlanVersion::getProjectId, projectId)
                .eq(RepairPlanVersion::getStatus, PLAN_SIGNING));
        if (signing != null && signing > 0) {
            throw new IllegalArgumentException("存在会签中的方案版本，不能重复提交: " + project.getProjectNo());
        }

        Relic relic = relicService.requireRelic(project.getRelicId());
        Integer maxNo = versionMapper.selectList(new LambdaQueryWrapper<RepairPlanVersion>()
                        .eq(RepairPlanVersion::getProjectId, projectId)
                        .orderByDesc(RepairPlanVersion::getVersionNo)
                        .last("LIMIT 1"))
                .stream().findFirst().map(RepairPlanVersion::getVersionNo).orElse(0);

        LocalDateTime now = LocalDateTime.now();
        RepairPlanVersion version = new RepairPlanVersion();
        version.setProjectId(projectId);
        version.setVersionNo(maxNo + 1);
        version.setPlanContent(req.getPlanContent());
        version.setRiskLevel(relic.getPreservationGrade());
        version.setStatus(PLAN_SIGNING);
        version.setInternalExpert(req.getInternalExpert());
        version.setExternalExpert(req.getExternalExpert());
        version.setCustodian(req.getCustodian());
        version.setSubmittedBy(req.getSubmittedBy());
        version.setSubmittedAt(now);
        versionMapper.insert(version);

        ledgerService.append(project.getRelicId(), projectId, null,
                ACTION_PLAN_SUBMIT, project.getProjectNo(),
                "方案V" + version.getVersionNo() + "提交会签，风险等级" + version.getRiskLevel()
                        + "，会签顺序" + String.join("->", signOrder(version)), now);
        return version;
    }

    /**
     * 会签签署：必须按配置的顺序、由指定的应签人本人签署；
     * 退回即封存版本只读，全部同意则版本通过。
     */
    @Transactional
    public RepairPlanSign sign(Long versionId, PlanSignRequest req) {
        RepairPlanVersion version = requireVersion(versionId);
        if (!PLAN_SIGNING.equals(version.getStatus())) {
            throw new IllegalArgumentException("方案V" + version.getVersionNo() + "已"
                    + (PLAN_RETURNED.equals(version.getStatus()) ? "退回" : "会签通过")
                    + "，保持只读，修订请提交新版本");
        }
        RepairProject project = requireProject(version.getProjectId());

        List<String> order = signOrder(version);
        List<RepairPlanSign> signed = listSigns(versionId);
        int stepNo = signed.size() + 1;
        String expectedRole = order.get(signed.size());
        if (!expectedRole.equals(req.getSignerRole())) {
            throw new IllegalArgumentException("未按会签顺序签署：当前应签角色为" + expectedRole
                    + "（第" + stepNo + "步），实际为" + req.getSignerRole());
        }
        String designated = designatedSigner(version, expectedRole);
        if (!designated.equals(req.getSignerName())) {
            throw new IllegalArgumentException("缺席专家不能由他人代签：当前应签人为" + designated
                    + "，实际签署人为" + req.getSignerName());
        }
        String decision = req.getDecision();
        if (!SIGN_DECISION_APPROVE.equals(decision) && !SIGN_DECISION_RETURN.equals(decision)) {
            throw new IllegalArgumentException("会签决定只能为 APPROVE/RETURN");
        }

        LocalDateTime now = LocalDateTime.now();
        RepairPlanSign sign = new RepairPlanSign();
        sign.setPlanVersionId(versionId);
        sign.setStepNo(stepNo);
        sign.setSignerRole(expectedRole);
        sign.setSignerName(req.getSignerName());
        sign.setDecision(decision);
        sign.setOpinion(req.getOpinion());
        sign.setAttachment(req.getAttachment());
        sign.setSignedAt(now);
        signMapper.insert(sign);

        if (SIGN_DECISION_RETURN.equals(decision)) {
            version.setStatus(PLAN_RETURNED);
            version.setClosedAt(now);
            versionMapper.updateById(version);
            ledgerService.append(project.getRelicId(), project.getId(), null,
                    ACTION_PLAN_RETURN, project.getProjectNo(),
                    "方案V" + version.getVersionNo() + "被" + req.getSignerName()
                            + "（" + expectedRole + "）退回，版本封存只读", now);
        } else if (stepNo == order.size()) {
            version.setStatus(PLAN_APPROVED);
            version.setClosedAt(now);
            versionMapper.updateById(version);
            ledgerService.append(project.getRelicId(), project.getId(), null,
                    ACTION_PLAN_APPROVE, project.getProjectNo(),
                    "方案V" + version.getVersionNo() + "会签全部通过，允许开工", now);
        } else {
            ledgerService.append(project.getRelicId(), project.getId(), null,
                    ACTION_PLAN_SIGN, project.getProjectNo(),
                    "方案V" + version.getVersionNo() + "第" + stepNo + "步会签："
                            + req.getSignerName() + "（" + expectedRole + "）同意", now);
        }
        return sign;
    }

    public RepairPlanVersion requireVersion(Long versionId) {
        RepairPlanVersion version = versionMapper.selectById(versionId);
        if (version == null) {
            throw new IllegalArgumentException("方案版本不存在: id=" + versionId);
        }
        return version;
    }

    public List<RepairPlanVersion> listVersions(Long projectId) {
        return versionMapper.selectList(new LambdaQueryWrapper<RepairPlanVersion>()
                .eq(RepairPlanVersion::getProjectId, projectId)
                .orderByAsc(RepairPlanVersion::getVersionNo));
    }

    public List<RepairPlanSign> listSigns(Long versionId) {
        return signMapper.selectList(new LambdaQueryWrapper<RepairPlanSign>()
                .eq(RepairPlanSign::getPlanVersionId, versionId)
                .orderByAsc(RepairPlanSign::getStepNo));
    }

    /** 该版本按风险等级配置出的会签顺序 */
    public List<String> signOrder(RepairPlanVersion version) {
        return countersignProperties.orderFor(version.getRiskLevel());
    }

    private String designatedSigner(RepairPlanVersion version, String role) {
        switch (role) {
            case SIGN_ROLE_INTERNAL:
                return version.getInternalExpert();
            case SIGN_ROLE_EXTERNAL:
                return version.getExternalExpert();
            case SIGN_ROLE_CUSTODIAN:
                return version.getCustodian();
            default:
                throw new IllegalArgumentException("未知会签角色: " + role);
        }
    }

    private RepairProject requireProject(Long projectId) {
        RepairProject project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new IllegalArgumentException("修复项目不存在: id=" + projectId);
        }
        return project;
    }
}
