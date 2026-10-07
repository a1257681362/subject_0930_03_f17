package com.evops.service;

import com.evops.constant.MuseumConstants;
import com.evops.dto.AcceptRequest;
import com.evops.dto.BootstrapResult;
import com.evops.dto.IntakeRequest;
import com.evops.dto.LoanRequest;
import com.evops.dto.MaterialCreateRequest;
import com.evops.dto.MaterialInboundRequest;
import com.evops.dto.MaterialReturnRequest;
import com.evops.dto.MaterialUsageRequest;
import com.evops.dto.PhotoDto;
import com.evops.dto.PlanSignRequest;
import com.evops.dto.PlanVersionCreateRequest;
import com.evops.dto.ProjectCreateRequest;
import com.evops.dto.RelicCreateRequest;
import com.evops.dto.ReturnRequest;
import com.evops.dto.StageRequest;
import com.evops.dto.UsageLineRequest;
import com.evops.entity.BootstrapLock;
import com.evops.entity.MaterialReview;
import com.evops.entity.MaterialUsage;
import com.evops.entity.Relic;
import com.evops.entity.RelicIntake;
import com.evops.entity.RelicLoan;
import com.evops.entity.RepairAcceptance;
import com.evops.entity.RepairProject;
import com.evops.entity.RepairStage;
import com.evops.mapper.BootstrapLockMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;

/**
 * 「博物馆文物修复档案起步」：
 * 首次请求播种一条走完 入藏 -> 借出 -> 归还 -> 修复（阶段）-> 验收 全链路的有效样本；
 * 重复请求直接返回同一条样本，不产生第二条结果。
 *
 * 幂等保证：t_bootstrap_lock 固定 id=1 的单行锁（CHECK 约束 + 唯一主键），
 * 播种与上锁在同一事务内提交；并发请求抢锁失败时回读已有样本返回。
 */
@Service
public class BootstrapService {

    static final String SAMPLE_RELIC_NO = "WW-2026-0001";
    static final String SAMPLE_INTAKE_NO = "RU-2026-0001";
    static final String SAMPLE_LOAN_NO = "LO-2026-0001";
    static final String SAMPLE_PROJECT_NO = "RP-2026-0001";

    static final String SAMPLE_PIGMENT_NO = "M-2026-0001";
    static final String SAMPLE_ADHESIVE_NO = "M-2026-0002";
    static final String SAMPLE_PROTECTANT_NO = "M-2026-0003";
    static final String SAMPLE_BATCH_PIGMENT = "B-2026-0001";
    static final String SAMPLE_BATCH_ADHESIVE = "B-2026-0002";
    static final String SAMPLE_BATCH_PROTECTANT = "B-2026-0003";
    static final String SAMPLE_USAGE_NO = "MU-2026-0001";
    static final String SAMPLE_RETURN_NO = "MR-2026-0001";

    private final BootstrapLockMapper lockMapper;
    private final RelicService relicService;
    private final IntakeService intakeService;
    private final LoanService loanService;
    private final RepairService repairService;
    private final RepairPlanService planService;
    private final MaterialAccountingService materialService;
    private final MaterialReviewScheduler reviewScheduler;
    private final TransactionTemplate transactionTemplate;

    public BootstrapService(BootstrapLockMapper lockMapper,
                            RelicService relicService,
                            IntakeService intakeService,
                            LoanService loanService,
                            RepairService repairService,
                            RepairPlanService planService,
                            MaterialAccountingService materialService,
                            MaterialReviewScheduler reviewScheduler,
                            PlatformTransactionManager transactionManager) {
        this.lockMapper = lockMapper;
        this.relicService = relicService;
        this.intakeService = intakeService;
        this.loanService = loanService;
        this.repairService = repairService;
        this.planService = planService;
        this.materialService = materialService;
        this.reviewScheduler = reviewScheduler;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public BootstrapResult bootstrap() {
        BootstrapLock lock = lockMapper.selectById(MuseumConstants.BOOTSTRAP_LOCK_ID);
        if (lock != null) {
            return toResult(false, lock);
        }
        try {
            BootstrapLock created = transactionTemplate.execute(status -> seed());
            return toResult(true, created);
        } catch (DuplicateKeyException e) {
            // 并发下别的请求已播种：回读同一行
            BootstrapLock existing = lockMapper.selectById(MuseumConstants.BOOTSTRAP_LOCK_ID);
            if (existing == null) {
                throw new IllegalStateException("起步锁状态异常，请重试");
            }
            return toResult(false, existing);
        }
    }

    /** 只读查看起步样本；尚未起步时返回 null，不触发播种 */
    public BootstrapResult peek() {
        BootstrapLock lock = lockMapper.selectById(MuseumConstants.BOOTSTRAP_LOCK_ID);
        return lock == null ? null : toResult(false, lock);
    }

    private BootstrapLock seed() {
        // 事务内二次检查，挡住通过快速路径后的并发
        BootstrapLock lock = lockMapper.selectById(MuseumConstants.BOOTSTRAP_LOCK_ID);
        if (lock != null) {
            return lock;
        }
        LocalDateTime now = LocalDateTime.now();

        // 1. 文物档案：材质、年代、来源、保存等级
        RelicCreateRequest relicReq = new RelicCreateRequest();
        relicReq.setRelicNo(SAMPLE_RELIC_NO);
        relicReq.setName("兽面纹青铜鼎（起步样本）");
        relicReq.setCategory("青铜器");
        relicReq.setMaterial("青铜");
        relicReq.setEra("商代晚期");
        relicReq.setSource("考古发掘移交");
        relicReq.setPreservationGrade(MuseumConstants.GRADE_ONE);
        relicReq.setDescription("起步样本：口沿微残、腹部有裂隙，用于演示完整修复台账链路。");
        Relic relic = relicService.create(relicReq);

        // 2. 入藏登记：照片、尺寸、库位
        IntakeRequest intakeReq = new IntakeRequest();
        intakeReq.setIntakeNo(SAMPLE_INTAKE_NO);
        intakeReq.setHeightMm(new BigDecimal("325.00"));
        intakeReq.setWidthMm(new BigDecimal("280.00"));
        intakeReq.setDepthMm(new BigDecimal("260.00"));
        intakeReq.setWeightG(new BigDecimal("5280.00"));
        intakeReq.setLocation("一号库·铜器区·A-03 柜");
        intakeReq.setIntakeTime(now.minusDays(30));
        intakeReq.setReceiver("库管员");
        intakeReq.setConditionNote("口沿残损一处，腹部纵向裂隙约 12cm，表面有硬结物。");
        PhotoDto photo = new PhotoDto();
        photo.setPhotoUrl("photos/WW-2026-0001/front.jpg");
        photo.setPhotoName("入藏正面照");
        photo.setViewType("正面");
        photo.setSortNo(1);
        intakeReq.setPhotos(Collections.singletonList(photo));
        RelicIntake intake = intakeService.register(relic.getId(), intakeReq);

        // 3. 借出 -> 归还（链路闭环后再进入修复）
        LoanRequest loanReq = new LoanRequest();
        loanReq.setLoanNo(SAMPLE_LOAN_NO);
        loanReq.setRelicId(relic.getId());
        loanReq.setBorrower("省展览馆");
        loanReq.setPurpose("『青铜文明』专题展览");
        loanReq.setLentAt(now.minusDays(20));
        loanReq.setExpectedReturnAt(now.minusDays(10));
        RelicLoan loan = loanService.lend(loanReq);

        ReturnRequest returnReq = new ReturnRequest();
        returnReq.setReturnedAt(now.minusDays(9));
        returnReq.setReturnCondition("品相与借出时一致，裂隙未见扩展。");
        returnReq.setReceiver("库管员");
        loanService.repay(loan.getId(), returnReq);

        // 4. 修复项目：委托人、方案
        ProjectCreateRequest projectReq = new ProjectCreateRequest();
        projectReq.setProjectNo(SAMPLE_PROJECT_NO);
        projectReq.setRelicId(relic.getId());
        projectReq.setClient("省博物馆藏品部");
        projectReq.setClientContact("0571-88000000");
        projectReq.setPlan("1. 表面清洗与缓释处理；2. 腹部裂隙矫形、补配随色；"
                + "3. 口沿补铸、作旧；4. 缓蚀封护并建立修复档案。");
        projectReq.setStartDate(now.minusDays(8).toLocalDate());
        projectReq.setExpectedEndDate(now.plusDays(7).toLocalDate());
        RepairProject project = repairService.createProject(projectReq);

        // 5. 方案会签：一级文物按配置顺序 外部专家 -> 馆内专家 -> 保管人员 依次签署，
        //    全部通过后才允许开工生成施工任务
        PlanVersionCreateRequest planReq = new PlanVersionCreateRequest();
        planReq.setPlanContent(projectReq.getPlan());
        planReq.setInternalExpert("馆内修复专家·张");
        planReq.setExternalExpert("院外文保专家·李");
        planReq.setCustodian("库房保管员·王");
        planReq.setSubmittedBy("修复项目负责人");
        com.evops.entity.RepairPlanVersion planVersion =
                planService.createVersion(project.getId(), planReq);
        planService.sign(planVersion.getId(), planSign("EXTERNAL", "院外文保专家·李",
                "裂隙走向已核，方案可行", "reports/WW-2026-0001/外部鉴定.pdf"));
        planService.sign(planVersion.getId(), planSign("INTERNAL", "馆内修复专家·张",
                "工艺路线与馆藏修复规范一致", "reports/WW-2026-0001/工艺评估.pdf"));
        planService.sign(planVersion.getId(), planSign("CUSTODIAN", "库房保管员·王",
                "修复期间库位与安保已落实", null));

        // 6. 开工、阶段结果、完成、验收
        repairService.startRepair(project.getId());

        // 6.1 修复材料：颜料/黏合剂/保护剂按批号入库 -> 工单领用 -> 差异复核 -> 退料
        seedMaterialAccounting(relic, project, now);

        StageRequest stage1 = new StageRequest();
        stage1.setStageName("清洗除锈");
        stage1.setStageResult("机械法配合低浓度乙醇清除表面硬结物，裸露基体做缓释处理。");
        stage1.setOperator("修复师甲");
        repairService.recordStage(project.getId(), stage1);

        StageRequest stage2 = new StageRequest();
        stage2.setStageName("裂隙矫形补配");
        stage2.setStageResult("腹部裂隙矫形后以同材质锡铅合金补配，随色作旧，接口贴合良好。");
        stage2.setOperator("修复师甲");
        repairService.recordStage(project.getId(), stage2);

        repairService.finishRepair(project.getId());

        AcceptRequest acceptReq = new AcceptRequest();
        acceptReq.setAcceptedBy("藏品部验收组");
        acceptReq.setResult(MuseumConstants.ACCEPT_PASS);
        acceptReq.setOpinion("补配部位牢固、随色协调，符合验收要求，同意结项。");
        repairService.accept(project.getId(), acceptReq);

        // 7. 上锁：固定单行，抢插失败将抛 DuplicateKeyException 并整体回滚
        BootstrapLock newLock = new BootstrapLock();
        newLock.setId(MuseumConstants.BOOTSTRAP_LOCK_ID);
        newLock.setRelicId(relic.getId());
        newLock.setProjectId(project.getId());
        newLock.setBootstrappedAt(LocalDateTime.now());
        lockMapper.insert(newLock);
        return newLock;
    }

    private void seedMaterialAccounting(Relic relic, RepairProject project, LocalDateTime now) {
        // 三类材料目录：颜料 / 黏合剂 / 保护剂
        seedMaterial(SAMPLE_PIGMENT_NO, "矿物颜料·石青", MuseumConstants.MATERIAL_PIGMENT, "g", "传统矿物颜料");
        seedMaterial(SAMPLE_ADHESIVE_NO, "可逆黏合剂·B72", MuseumConstants.MATERIAL_ADHESIVE, "ml", "乙酸乙酯体系");
        seedMaterial(SAMPLE_PROTECTANT_NO, "封护保护剂·丙烯酸树脂", MuseumConstants.MATERIAL_PROTECTANT, "ml", "表面缓蚀封护");

        // 按批号入库（同批号重复入库累加库存）；起步批号均在有效期内
        seedInbound(SAMPLE_PIGMENT_NO, SAMPLE_BATCH_PIGMENT, new BigDecimal("100.000"),
                now.toLocalDate().minusDays(60), now.toLocalDate().plusDays(365), "传统材料供应站");
        seedInbound(SAMPLE_ADHESIVE_NO, SAMPLE_BATCH_ADHESIVE, new BigDecimal("200.000"),
                now.toLocalDate().minusDays(30), now.toLocalDate().plusDays(180), "修复材料中心");
        seedInbound(SAMPLE_PROTECTANT_NO, SAMPLE_BATCH_PROTECTANT, new BigDecimal("500.000"),
                now.toLocalDate().minusDays(15), now.toLocalDate().plusDays(30), "修复材料中心");

        // 工单领用：记录批号、计划/实际用量、部位、操作者；黏合剂行实际用量超阈值
        MaterialUsageRequest usageReq = new MaterialUsageRequest();
        usageReq.setUsageNo(SAMPLE_USAGE_NO);
        usageReq.setProjectId(project.getId());
        usageReq.setNote("起步样本：腹部裂隙矫形补配与口沿补铸用料");
        java.util.List<UsageLineRequest> lines = new java.util.ArrayList<>();
        lines.add(usageLine(SAMPLE_BATCH_PIGMENT, "10.000", "11.000", "口沿补配随色", "修复师甲"));
        lines.add(usageLine(SAMPLE_BATCH_ADHESIVE, "50.000", "80.000", "腹部裂隙接口", "修复师甲"));
        lines.add(usageLine(SAMPLE_BATCH_PROTECTANT, "100.000", "100.000", "整体表面封护", "修复师乙"));
        usageReq.setLines(lines);
        MaterialUsage usage = materialService.issue(usageReq);

        // 模拟定时任务重启后的补扫：差异行进复核（黏合剂行偏差 60%，超过 20% 阈值）
        reviewScheduler.scanOnce();

        // 退料关联原领用单：颜料行部分退回 5g，库存加回原批号
        MaterialReturnRequest returnReq = new MaterialReturnRequest();
        returnReq.setReturnNo(SAMPLE_RETURN_NO);
        returnReq.setUsageId(usage.getId());
        returnReq.setLineId(materialService.listLines(usage.getId()).get(0).getId());
        returnReq.setReturnQty(new BigDecimal("5.000"));
        returnReq.setReason("口沿随色余量退回");
        returnReq.setReturnedBy("修复师甲");
        materialService.returnMaterial(returnReq);
    }

    private void seedMaterial(String no, String name, String category, String unit, String spec) {
        MaterialCreateRequest req = new MaterialCreateRequest();
        req.setMaterialNo(no);
        req.setName(name);
        req.setCategory(category);
        req.setUnit(unit);
        req.setSpec(spec);
        materialService.createMaterial(req);
    }

    private void seedInbound(String materialNo, String batchNo, BigDecimal qty,
                             java.time.LocalDate produced, java.time.LocalDate expire, String supplier) {
        MaterialInboundRequest req = new MaterialInboundRequest();
        req.setMaterialNo(materialNo);
        req.setBatchNo(batchNo);
        req.setQty(qty);
        req.setProducedDate(produced);
        req.setExpireDate(expire);
        req.setSupplier(supplier);
        req.setInboundBy("库管员");
        materialService.inbound(req);
    }

    private PlanSignRequest planSign(String role, String name, String opinion, String attachment) {
        PlanSignRequest req = new PlanSignRequest();
        req.setSignerRole(role);
        req.setSignerName(name);
        req.setDecision(MuseumConstants.SIGN_DECISION_APPROVE);
        req.setOpinion(opinion);
        req.setAttachment(attachment);
        return req;
    }

    private UsageLineRequest usageLine(String batchNo, String plan, String actual,
                                       String part, String operator) {
        UsageLineRequest line = new UsageLineRequest();
        line.setBatchNo(batchNo);
        line.setPlanQty(new BigDecimal(plan));
        line.setActualQty(new BigDecimal(actual));
        line.setApplyPart(part);
        line.setOperator(operator);
        return line;
    }

    private BootstrapResult toResult(boolean created, BootstrapLock lock) {
        Relic relic = relicService.requireRelic(lock.getRelicId());
        RepairProject project = repairService.requireProject(lock.getProjectId());
        RelicIntake intake = intakeService.getByRelic(relic.getId());
        java.util.List<RelicLoan> loans = loanService.listByRelic(relic.getId());
        java.util.List<RepairStage> stages = repairService.listStages(project.getId());
        RepairAcceptance acceptance = repairService.getAcceptance(project.getId());
        java.util.List<MaterialUsage> usages = materialService.listUsagesByProject(project.getId());
        MaterialUsage sampleUsage = usages.stream()
                .filter(u -> SAMPLE_USAGE_NO.equals(u.getUsageNo()))
                .findFirst().orElse(usages.isEmpty() ? null : usages.get(0));
        java.util.List<com.evops.entity.MaterialUsageLine> usageLines =
                sampleUsage == null ? java.util.Collections.emptyList()
                        : materialService.listLines(sampleUsage.getId());
        java.util.List<MaterialReview> reviews = materialService.listReviews(null);
        long openReviewCount = reviews.stream()
                .filter(r -> MuseumConstants.REVIEW_OPEN.equals(r.getStatus())
                        && project.getId().equals(r.getProjectId()))
                .count();

        BootstrapResult result = new BootstrapResult();
        result.setCreated(created);
        result.setMessage(created
                ? "已提交有效样本：入藏-借出-归还-修复-验收链路完整"
                : "起步样本已存在，本次为重复请求，未产生第二条结果");
        result.setRelicId(relic.getId());
        result.setRelicNo(relic.getRelicNo());
        result.setIntakeId(intake == null ? null : intake.getId());
        result.setIntakeNo(intake == null ? null : intake.getIntakeNo());
        RelicLoan sampleLoan = loans.stream()
                .filter(l -> SAMPLE_LOAN_NO.equals(l.getLoanNo()))
                .findFirst().orElse(loans.isEmpty() ? null : loans.get(0));
        result.setLoanId(sampleLoan == null ? null : sampleLoan.getId());
        result.setLoanNo(sampleLoan == null ? null : sampleLoan.getLoanNo());
        result.setProjectId(project.getId());
        result.setProjectNo(project.getProjectNo());
        result.setStageCount(stages.size());
        result.setAcceptanceId(acceptance == null ? null : acceptance.getId());
        result.setMaterialLineCount(usageLines.size());
        result.setOpenReviewCount((int) openReviewCount);
        result.setUsageId(sampleUsage == null ? null : sampleUsage.getId());
        result.setUsageNo(sampleUsage == null ? null : sampleUsage.getUsageNo());
        return result;
    }
}
