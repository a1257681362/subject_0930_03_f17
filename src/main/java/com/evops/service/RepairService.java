package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.dto.AcceptRequest;
import com.evops.dto.ProjectCreateRequest;
import com.evops.dto.StageRequest;
import com.evops.entity.Relic;
import com.evops.entity.RelicLoan;
import com.evops.entity.RepairAcceptance;
import com.evops.entity.RepairPlanVersion;
import com.evops.entity.RepairProject;
import com.evops.entity.RepairStage;
import com.evops.mapper.RelicLoanMapper;
import com.evops.mapper.RepairAcceptanceMapper;
import com.evops.mapper.RepairPlanVersionMapper;
import com.evops.mapper.RepairProjectMapper;
import com.evops.mapper.RepairStageMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static com.evops.constant.MuseumConstants.*;

/**
 * 修复项目链路：
 *   立项 PLANNED -> 开工 REPAIRING -> 登记阶段结果（可多次）
 *   -> 修复完成 REPAIRED -> 验收 ACCEPTED（闭环）
 * PLANNED / REPAIRING 可作废 CANCELLED；作废只改状态。
 */
@Service
public class RepairService {

    private static final List<String> ACCEPT_RESULTS =
            Arrays.asList(ACCEPT_PASS, ACCEPT_CONDITIONAL);

    private static final List<String> GRADES =
            Arrays.asList(GRADE_ONE, GRADE_TWO, GRADE_THREE);

    private final RepairProjectMapper projectMapper;
    private final RepairStageMapper stageMapper;
    private final RepairAcceptanceMapper acceptanceMapper;
    private final RepairPlanVersionMapper planVersionMapper;
    private final RelicLoanMapper loanMapper;
    private final RelicService relicService;
    private final LedgerService ledgerService;
    private final RepairCompensationService compensationService;

    public RepairService(RepairProjectMapper projectMapper,
                         RepairStageMapper stageMapper,
                         RepairAcceptanceMapper acceptanceMapper,
                         RepairPlanVersionMapper planVersionMapper,
                         RelicLoanMapper loanMapper,
                         RelicService relicService,
                         LedgerService ledgerService,
                         RepairCompensationService compensationService) {
        this.projectMapper = projectMapper;
        this.stageMapper = stageMapper;
        this.acceptanceMapper = acceptanceMapper;
        this.planVersionMapper = planVersionMapper;
        this.loanMapper = loanMapper;
        this.relicService = relicService;
        this.ledgerService = ledgerService;
        this.compensationService = compensationService;
    }

    @Transactional
    public RepairProject createProject(ProjectCreateRequest req) {
        Relic relic = relicService.requireActiveRelic(req.getRelicId());

        if (projectMapper.selectCount(new LambdaQueryWrapper<RepairProject>()
                .eq(RepairProject::getProjectNo, req.getProjectNo())) > 0) {
            throw new IllegalArgumentException("项目编号已存在: " + req.getProjectNo());
        }

        RepairProject project = new RepairProject();
        project.setProjectNo(req.getProjectNo());
        project.setRelicId(relic.getId());
        project.setClient(req.getClient());
        project.setClientContact(req.getClientContact());
        project.setPlan(req.getPlan());
        project.setStartDate(req.getStartDate());
        project.setExpectedEndDate(req.getExpectedEndDate());
        project.setStatus(PROJECT_PLANNED);
        projectMapper.insert(project);
        return project;
    }

    /** 开工：PLANNED -> REPAIRING；文物借出未还时不允许开工；方案会签通过才允许生成施工任务 */
    @Transactional
    public RepairProject startRepair(Long projectId) {
        RepairProject project = requireProject(projectId);
        if (!PROJECT_PLANNED.equals(project.getStatus())) {
            throw new IllegalArgumentException("项目不是已立项状态，不能开工: " + project.getProjectNo());
        }
        Long openLoans = loanMapper.selectCount(new LambdaQueryWrapper<RelicLoan>()
                .eq(RelicLoan::getRelicId, project.getRelicId())
                .eq(RelicLoan::getStatus, LOAN_LENT));
        if (openLoans != null && openLoans > 0) {
            throw new IllegalArgumentException("文物尚在外借，不能开始修复");
        }
        RepairPlanVersion latestPlan = planVersionMapper.selectList(
                        new LambdaQueryWrapper<RepairPlanVersion>()
                                .eq(RepairPlanVersion::getProjectId, projectId)
                                .orderByDesc(RepairPlanVersion::getVersionNo)
                                .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
        if (latestPlan == null || !PLAN_APPROVED.equals(latestPlan.getStatus())) {
            throw new IllegalArgumentException("方案会签未完成，不能生成施工任务（开工）: "
                    + project.getProjectNo());
        }
        project.setStatus(PROJECT_REPAIRING);
        if (project.getStartDate() == null) {
            project.setStartDate(LocalDate.now());
        }
        projectMapper.updateById(project);

        ledgerService.append(project.getRelicId(), projectId, null,
                ACTION_REPAIR_START, project.getProjectNo(),
                "修复项目开工，委托人：" + project.getClient(), LocalDateTime.now());
        return project;
    }

    /** 登记阶段结果：仅修复中可登记，阶段序号项目内自动递增 */
    @Transactional
    public RepairStage recordStage(Long projectId, StageRequest req) {
        RepairProject project = requireProject(projectId);
        if (!PROJECT_REPAIRING.equals(project.getStatus())) {
            throw new IllegalArgumentException("项目不在修复中，不能登记阶段结果: " + project.getProjectNo());
        }
        Integer maxNo = stageMapper.selectList(new LambdaQueryWrapper<RepairStage>()
                        .eq(RepairStage::getProjectId, projectId)
                        .orderByDesc(RepairStage::getStageNo)
                        .last("LIMIT 1"))
                .stream().findFirst().map(RepairStage::getStageNo).orElse(0);

        LocalDateTime now = LocalDateTime.now();
        RepairStage stage = new RepairStage();
        stage.setProjectId(projectId);
        stage.setStageNo(maxNo + 1);
        stage.setStageName(req.getStageName());
        stage.setStageResult(req.getStageResult());
        stage.setOperator(req.getOperator());
        stage.setRecordedAt(now);
        stageMapper.insert(stage);

        ledgerService.append(project.getRelicId(), projectId, null,
                ACTION_REPAIR_STAGE, project.getProjectNo(),
                "阶段" + stage.getStageNo() + "【" + req.getStageName() + "】结果已登记", now);
        return stage;
    }

    /** 修复完成：REPAIRING -> REPAIRED，等待验收 */
    @Transactional
    public RepairProject finishRepair(Long projectId) {
        RepairProject project = requireProject(projectId);
        if (!PROJECT_REPAIRING.equals(project.getStatus())) {
            throw new IllegalArgumentException("项目不在修复中，不能完成: " + project.getProjectNo());
        }
        Long stageCount = stageMapper.selectCount(new LambdaQueryWrapper<RepairStage>()
                .eq(RepairStage::getProjectId, projectId));
        if (stageCount == null || stageCount == 0) {
            throw new IllegalArgumentException("尚未登记任何阶段结果，不能完成修复");
        }
        LocalDateTime now = LocalDateTime.now();
        project.setStatus(PROJECT_REPAIRED);
        project.setFinishDate(LocalDate.now());
        projectMapper.updateById(project);

        ledgerService.append(project.getRelicId(), projectId, null,
                ACTION_REPAIR_FINISH, project.getProjectNo(),
                "修复完成，共 " + stageCount + " 个阶段，待验收", now);
        return project;
    }

    /** 验收：REPAIRED -> ACCEPTED，一个项目仅一条验收记录 */
    @Transactional
    public RepairAcceptance accept(Long projectId, AcceptRequest req) {
        RepairProject project = requireProject(projectId);
        if (!PROJECT_REPAIRED.equals(project.getStatus())) {
            throw new IllegalArgumentException("项目未处于待验收状态，不能验收: " + project.getProjectNo());
        }
        String result = (req.getResult() == null || req.getResult().isEmpty())
                ? ACCEPT_PASS : req.getResult();
        if (!ACCEPT_RESULTS.contains(result)) {
            throw new IllegalArgumentException("验收结论只能为 PASS/CONDITIONAL");
        }
        // museum-10：附条件通过（部分成功）必须登记验收后保护等级
        String gradeAfter = req.getGradeAfter() == null ? null : req.getGradeAfter().trim();
        if (ACCEPT_CONDITIONAL.equals(result)
                && (gradeAfter == null || !GRADES.contains(gradeAfter))) {
            throw new IllegalArgumentException("验收附条件通过（部分成功）必须登记验收后保护等级 ONE/TWO/THREE");
        }

        LocalDateTime now = LocalDateTime.now();
        RepairAcceptance acceptance = new RepairAcceptance();
        acceptance.setProjectId(projectId);
        acceptance.setAcceptedBy(req.getAcceptedBy());
        acceptance.setAcceptedAt(now);
        acceptance.setResult(result);
        acceptance.setOpinion(req.getOpinion());
        acceptanceMapper.insert(acceptance);

        project.setStatus(PROJECT_ACCEPTED);
        projectMapper.updateById(project);

        ledgerService.append(project.getRelicId(), projectId, null,
                ACTION_ACCEPT, project.getProjectNo(),
                "验收" + (ACCEPT_PASS.equals(result) ? "通过" : "附条件通过")
                        + "，验收人：" + req.getAcceptedBy(), now);

        // 部分成功：等级立即调整并成对开立保护等级/借阅补偿
        if (ACCEPT_CONDITIONAL.equals(result)) {
            Relic relic = relicService.requireRelic(project.getRelicId());
            compensationService.openOnConditionalAccept(project, acceptance,
                    relic.getPreservationGrade(), gradeAfter, req.getOpinion());
        }
        return acceptance;
    }

    /** 作废：仅 PLANNED / REPAIRING 可作废，记录保留 */
    @Transactional
    public RepairProject cancel(Long projectId, String reason) {
        RepairProject project = requireProject(projectId);
        if (!PROJECT_PLANNED.equals(project.getStatus())
                && !PROJECT_REPAIRING.equals(project.getStatus())) {
            throw new IllegalArgumentException("当前状态不允许作废: " + project.getStatus());
        }
        LocalDateTime now = LocalDateTime.now();
        project.setStatus(PROJECT_CANCELLED);
        project.setCancelReason(reason);
        project.setCancelledAt(now);
        projectMapper.updateById(project);

        ledgerService.append(project.getRelicId(), projectId, null,
                ACTION_CANCEL, project.getProjectNo(),
                "修复项目作废，原因：" + reason, now);
        return project;
    }

    public RepairProject requireProject(Long projectId) {
        RepairProject project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new IllegalArgumentException("修复项目不存在: id=" + projectId);
        }
        return project;
    }

    public List<RepairProject> listByRelic(Long relicId) {
        return projectMapper.selectList(new LambdaQueryWrapper<RepairProject>()
                .eq(RepairProject::getRelicId, relicId)
                .orderByDesc(RepairProject::getId));
    }

    public List<RepairStage> listStages(Long projectId) {
        return stageMapper.selectList(new LambdaQueryWrapper<RepairStage>()
                .eq(RepairStage::getProjectId, projectId)
                .orderByAsc(RepairStage::getStageNo));
    }

    public RepairAcceptance getAcceptance(Long projectId) {
        return acceptanceMapper.selectOne(new LambdaQueryWrapper<RepairAcceptance>()
                .eq(RepairAcceptance::getProjectId, projectId));
    }
}
