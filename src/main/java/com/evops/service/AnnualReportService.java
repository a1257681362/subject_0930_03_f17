package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.evops.dto.AnnualCaliberPublishRequest;
import com.evops.dto.AnnualCaliberSpec;
import com.evops.entity.AcceptReport;
import com.evops.entity.AnnualCaliberVersion;
import com.evops.entity.AnnualReport;
import com.evops.entity.AnnualReportAlert;
import com.evops.entity.AnnualReportAnomaly;
import com.evops.entity.AnnualReportJobLog;
import com.evops.entity.AnnualReportLoanLine;
import com.evops.entity.AnnualReportMaterialLine;
import com.evops.entity.AnnualReportProjectLine;
import com.evops.entity.EnvEvent;
import com.evops.entity.HallRelic;
import com.evops.entity.LoanAgreement;
import com.evops.entity.LoanAgreementVersion;
import com.evops.entity.MaterialReturn;
import com.evops.entity.MaterialUsage;
import com.evops.entity.MaterialUsageLine;
import com.evops.entity.Relic;
import com.evops.entity.RepairAcceptance;
import com.evops.entity.RepairEquipmentFault;
import com.evops.entity.RepairProject;
import com.evops.entity.RepairTimesheet;
import com.evops.entity.RepairTimesheetLine;
import com.evops.mapper.AcceptReportMapper;
import com.evops.mapper.AnnualCaliberVersionMapper;
import com.evops.mapper.AnnualReportAlertMapper;
import com.evops.mapper.AnnualReportAnomalyMapper;
import com.evops.mapper.AnnualReportJobLogMapper;
import com.evops.mapper.AnnualReportLoanLineMapper;
import com.evops.mapper.AnnualReportMaterialLineMapper;
import com.evops.mapper.AnnualReportProjectLineMapper;
import com.evops.mapper.AnnualReportMapper;
import com.evops.mapper.EnvEventMapper;
import com.evops.mapper.HallRelicMapper;
import com.evops.mapper.LoanAgreementMapper;
import com.evops.mapper.LoanAgreementVersionMapper;
import com.evops.mapper.MaterialReturnMapper;
import com.evops.mapper.MaterialUsageLineMapper;
import com.evops.mapper.MaterialUsageMapper;
import com.evops.mapper.RelicMapper;
import com.evops.mapper.RepairAcceptanceMapper;
import com.evops.mapper.RepairEquipmentFaultMapper;
import com.evops.mapper.RepairProjectMapper;
import com.evops.mapper.RepairTimesheetLineMapper;
import com.evops.mapper.RepairTimesheetMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static com.evops.constant.MuseumConstants.*;

/**
 * 文物修复年度报表（museum-16）核心服务。
 *
 * <p>口径版本化、归档快照不可变、撤回重提可辨、缺验收资料标待补、
 * 异常凭证只追加、Quartz 失败告警留日志、导出按馆区/保密级别裁剪。</p>
 */
@Service
public class AnnualReportService {

    private static final Logger log = LoggerFactory.getLogger(AnnualReportService.class);

    /** 口径归属时间字段白名单（防止任意列名注入） */
    private static final Set<String> PROJECT_DATE_FIELDS = new HashSet<>();
    private static final Set<String> MATERIAL_DATE_FIELDS = new HashSet<>();
    private static final Set<String> LOAN_DATE_FIELDS = new HashSet<>();
    private static final Set<String> ENV_DATE_FIELDS = new HashSet<>();
    private static final Set<String> FAULT_DATE_FIELDS = new HashSet<>();

    static {
        PROJECT_DATE_FIELDS.add("CREATE_TIME");
        PROJECT_DATE_FIELDS.add("FINISH_DATE");
        MATERIAL_DATE_FIELDS.add("USAGE_AT");
        LOAN_DATE_FIELDS.add("SIGNED_AT");
        ENV_DATE_FIELDS.add("FIRST_AT");
        ENV_DATE_FIELDS.add("LAST_AT");
        FAULT_DATE_FIELDS.add("FAULT_AT");
    }

    private final AnnualCaliberVersionMapper caliberMapper;
    private final AnnualReportMapper reportMapper;
    private final AnnualReportProjectLineMapper projectLineMapper;
    private final AnnualReportLoanLineMapper loanLineMapper;
    private final AnnualReportMaterialLineMapper materialLineMapper;
    private final AnnualReportAnomalyMapper anomalyMapper;
    private final AnnualReportJobLogMapper jobLogMapper;
    private final AnnualReportAlertMapper alertMapper;
    private final RelicMapper relicMapper;
    private final RepairProjectMapper projectMapper;
    private final RepairAcceptanceMapper acceptanceMapper;
    private final AcceptReportMapper acceptReportMapper;
    private final MaterialUsageMapper usageMapper;
    private final MaterialUsageLineMapper usageLineMapper;
    private final MaterialReturnMapper returnMapper;
    private final LoanAgreementMapper loanAgreementMapper;
    private final LoanAgreementVersionMapper agreementVersionMapper;
    private final EnvEventMapper envEventMapper;
    private final HallRelicMapper hallRelicMapper;
    private final RepairEquipmentFaultMapper faultMapper;
    private final RepairTimesheetLineMapper timesheetLineMapper;
    private final RepairTimesheetMapper timesheetMapper;
    private final AnnualReportLedgerService ledgerService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate requiresNewTemplate;

    public AnnualReportService(AnnualCaliberVersionMapper caliberMapper,
                               AnnualReportMapper reportMapper,
                               AnnualReportProjectLineMapper projectLineMapper,
                               AnnualReportLoanLineMapper loanLineMapper,
                               AnnualReportMaterialLineMapper materialLineMapper,
                               AnnualReportAnomalyMapper anomalyMapper,
                               AnnualReportJobLogMapper jobLogMapper,
                               AnnualReportAlertMapper alertMapper,
                               RelicMapper relicMapper,
                               RepairProjectMapper projectMapper,
                               RepairAcceptanceMapper acceptanceMapper,
                               AcceptReportMapper acceptReportMapper,
                               MaterialUsageMapper usageMapper,
                               MaterialUsageLineMapper usageLineMapper,
                               MaterialReturnMapper returnMapper,
                               LoanAgreementMapper loanAgreementMapper,
                               LoanAgreementVersionMapper agreementVersionMapper,
                               EnvEventMapper envEventMapper,
                               HallRelicMapper hallRelicMapper,
                               RepairEquipmentFaultMapper faultMapper,
                               RepairTimesheetLineMapper timesheetLineMapper,
                               RepairTimesheetMapper timesheetMapper,
                               AnnualReportLedgerService ledgerService,
                               ObjectMapper objectMapper,
                               PlatformTransactionManager transactionManager) {
        this.caliberMapper = caliberMapper;
        this.reportMapper = reportMapper;
        this.projectLineMapper = projectLineMapper;
        this.loanLineMapper = loanLineMapper;
        this.materialLineMapper = materialLineMapper;
        this.anomalyMapper = anomalyMapper;
        this.jobLogMapper = jobLogMapper;
        this.alertMapper = alertMapper;
        this.relicMapper = relicMapper;
        this.projectMapper = projectMapper;
        this.acceptanceMapper = acceptanceMapper;
        this.acceptReportMapper = acceptReportMapper;
        this.usageMapper = usageMapper;
        this.usageLineMapper = usageLineMapper;
        this.returnMapper = returnMapper;
        this.loanAgreementMapper = loanAgreementMapper;
        this.agreementVersionMapper = agreementVersionMapper;
        this.envEventMapper = envEventMapper;
        this.hallRelicMapper = hallRelicMapper;
        this.faultMapper = faultMapper;
        this.timesheetLineMapper = timesheetLineMapper;
        this.timesheetMapper = timesheetMapper;
        this.ledgerService = ledgerService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ------------------------------------------------------------------
    // 口径版本
    // ------------------------------------------------------------------

    /** 发布口径新版本：另出新版本，旧 ACTIVE 版本同事务置 SUPERSEDED 原样保留。 */
    @org.springframework.transaction.annotation.Transactional
    public AnnualCaliberVersion publishCaliber(AnnualCaliberPublishRequest req) {
        AnnualCaliberSpec spec = req.getSpec() == null ? AnnualCaliberSpec.defaults() : req.getSpec();
        validateSpec(spec);
        Integer maxNo = caliberMapper.selectList(new LambdaQueryWrapper<AnnualCaliberVersion>()
                        .orderByDesc(AnnualCaliberVersion::getVersionNo).last("LIMIT 1"))
                .stream().findFirst().map(AnnualCaliberVersion::getVersionNo).orElse(0);
        LocalDateTime now = LocalDateTime.now();

        AnnualCaliberVersion version = new AnnualCaliberVersion();
        version.setVersionNo(maxNo + 1);
        version.setName(req.getName());
        version.setCaliberJson(writeSpec(spec));
        version.setStatus(CALIBER_ACTIVE);
        version.setChangeReason(req.getChangeReason());
        version.setPublishedBy(req.getPublishedBy());
        version.setPublishedAt(now);
        caliberMapper.insert(version);

        // 旧生效版本置 SUPERSEDED（只追加，字段原样保留不改写）
        List<AnnualCaliberVersion> actives = caliberMapper.selectList(
                new LambdaQueryWrapper<AnnualCaliberVersion>()
                        .eq(AnnualCaliberVersion::getStatus, CALIBER_ACTIVE)
                        .ne(AnnualCaliberVersion::getId, version.getId()));
        for (AnnualCaliberVersion old : actives) {
            old.setStatus(CALIBER_SUPERSEDED);
            old.setSupersededAt(now);
            caliberMapper.updateById(old);
        }

        ledgerService.append(null, version.getId(), ACTION_ANNUAL_CALIBER_PUBLISH,
                "CALIBER-V" + version.getVersionNo(),
                "发布年度报表指标口径版本 V" + version.getVersionNo() + "《" + version.getName()
                        + "》" + (maxNo == 0 ? "（首版）" : "，旧版本置 SUPERSEDED 只追加保留"), now);
        return version;
    }

    public List<AnnualCaliberVersion> listCalibers() {
        return caliberMapper.selectList(new LambdaQueryWrapper<AnnualCaliberVersion>()
                .orderByDesc(AnnualCaliberVersion::getVersionNo));
    }

    public AnnualCaliberVersion activeCaliber() {
        return caliberMapper.selectOne(new LambdaQueryWrapper<AnnualCaliberVersion>()
                .eq(AnnualCaliberVersion::getStatus, CALIBER_ACTIVE).last("LIMIT 1"));
    }

    /** 解析报表核对所用口径：显式指定优先，否则取当前 ACTIVE；报表绑定后不再改变。 */
    private AnnualCaliberVersion resolveCaliber(Long caliberVersionId) {
        AnnualCaliberVersion version;
        if (caliberVersionId != null) {
            version = caliberMapper.selectById(caliberVersionId);
            if (version == null) {
                throw new IllegalArgumentException("口径版本不存在: id=" + caliberVersionId);
            }
            if (CALIBER_DRAFT.equals(version.getStatus())) {
                throw new IllegalArgumentException("口径版本 V" + version.getVersionNo()
                        + " 仍是草稿，未发布不能用于报表核对");
            }
        } else {
            version = activeCaliber();
            if (version == null) {
                throw new IllegalArgumentException("尚无生效的指标口径版本，请先发布口径");
            }
        }
        return version;
    }

    private void validateSpec(AnnualCaliberSpec spec) {
        if (spec.getProject() == null || spec.getCompleted() == null || spec.getMaterial() == null
                || spec.getLoan() == null || spec.getAnomaly() == null) {
            throw new IllegalArgumentException("口径必须完整包含立项/完成/材料/借展/异常五项指标");
        }
        requireField("立项归属时间", PROJECT_DATE_FIELDS, spec.getProject().getDateField());
        requireField("完成归属时间", PROJECT_DATE_FIELDS, spec.getCompleted().getDateField());
        requireField("材料归属时间", MATERIAL_DATE_FIELDS, spec.getMaterial().getDateField());
        requireField("借展归属时间", LOAN_DATE_FIELDS, spec.getLoan().getDateField());
        requireField("环境异常归属时间", ENV_DATE_FIELDS, spec.getAnomaly().getEnvDateField());
        requireField("设备故障归属时间", FAULT_DATE_FIELDS, spec.getAnomaly().getFaultDateField());
        if (spec.getProject().getStatuses() == null || spec.getProject().getStatuses().isEmpty()
                || spec.getCompleted().getStatuses() == null || spec.getCompleted().getStatuses().isEmpty()) {
            throw new IllegalArgumentException("立项数与完成数口径必须声明计入状态集合");
        }
        if (spec.getAnomaly().getTypes() == null || spec.getAnomaly().getTypes().isEmpty()) {
            throw new IllegalArgumentException("异常事件口径必须声明至少一种计入类型");
        }
        for (String type : spec.getAnomaly().getTypes()) {
            if (!ANOMALY_ENV_EVENT.equals(type) && !ANOMALY_EQUIPMENT_FAULT.equals(type)) {
                throw new IllegalArgumentException("未知异常事件类型: " + type);
            }
        }
    }

    private void requireField(String label, Set<String> allowed, String actual) {
        if (actual == null || !allowed.contains(actual)) {
            throw new IllegalArgumentException(label + "字段口径非法: " + actual + "，允许值 " + allowed);
        }
    }

    private String writeSpec(AnnualCaliberSpec spec) {
        try {
            return objectMapper.writeValueAsString(spec);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("口径序列化失败", e);
        }
    }

    private AnnualCaliberSpec readSpec(String json) {
        try {
            return objectMapper.readValue(json, AnnualCaliberSpec.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("报表绑定的口径版本内容无法解析: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // 生成（含 Quartz 任务日志 + 失败告警）
    // ------------------------------------------------------------------

    /**
     * 带任务日志的生成入口（手动/Quartz 定时共用）：
     * 先在独立事务落一条 RUNNING 日志，成功置 SUCCESS；失败则在独立事务置 FAILED、
     * 追加 OPEN 告警与台账（日志与告警不随业务事务回滚），再把异常抛给调用方。
     */
    public AnnualReport runGeneration(Integer year, Long caliberVersionId, String campusScope,
                                      String generatedBy, String triggeredBy, String fireKey) {
        LocalDateTime startedAt = LocalDateTime.now();
        AnnualReportJobLog jobLog = requiresNewTemplate.execute(s -> {
            AnnualReportJobLog entry = new AnnualReportJobLog();
            entry.setReportYear(year);
            entry.setCaliberVersionId(caliberVersionId);
            entry.setCampusScope(campusScope);
            entry.setTriggeredBy(triggeredBy);
            entry.setFireKey(fireKey);
            entry.setStatus(JOB_RUNNING);
            entry.setStartedAt(startedAt);
            jobLogMapper.insert(entry);
            return entry;
        });

        try {
            AnnualReport report = transactionTemplate.execute(s -> doGenerate(
                    year, caliberVersionId, campusScope, generatedBy, triggeredBy, jobLog.getId(), null));
            requiresNewTemplate.execute(s -> {
                AnnualReportJobLog update = jobLogMapper.selectById(jobLog.getId());
                update.setStatus(JOB_SUCCESS);
                update.setReportId(report.getId());
                update.setReportNo(report.getReportNo());
                update.setFinishedAt(LocalDateTime.now());
                jobLogMapper.updateById(update);
                return null;
            });
            return report;
        } catch (RuntimeException e) {
            recordJobFailure(jobLog.getId(), year, e);
            throw e;
        }
    }

    /** 业务事务失败后，在独立事务保留 FAILED 日志并追加 OPEN 告警，绝不覆盖/删除日志。 */
    private void recordJobFailure(Long jobLogId, Integer year, RuntimeException e) {
        try {
            requiresNewTemplate.execute(s -> {
                String message = truncate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), 900);
                AnnualReportJobLog update = jobLogMapper.selectById(jobLogId);
                update.setStatus(JOB_FAILED);
                update.setErrorMessage(message);
                update.setFinishedAt(LocalDateTime.now());
                jobLogMapper.updateById(update);

                AnnualReportAlert alert = new AnnualReportAlert();
                alert.setAlertNo(String.format("ARA-%06d", alertMapper.nextAlertSeq()));
                alert.setJobLogId(jobLogId);
                alert.setReportYear(year);
                alert.setReason(truncate("年度报表生成失败：" + message, 950));
                alert.setStatus(ANNUAL_ALERT_OPEN);
                alert.setCreatedAt(LocalDateTime.now());
                alertMapper.insert(alert);

                ledgerService.append(null, update.getCaliberVersionId(), ACTION_ANNUAL_JOB_FAILED,
                        alert.getAlertNo(), alert.getReason(), LocalDateTime.now());
                return null;
            });
        } catch (Exception ex) {
            // 告警落库本身失败不能吞掉原始异常，仅记录错误日志
            log.error("年度报表失败告警落库异常 jobLogId={}", jobLogId, ex);
        }
    }

    /**
     * 生成一份不可变年度报表快照（一个事务）。
     * 报表绑定解析到的口径版本 id；之后口径再发布新版本，本报表核对仍按原版本。
     */
    public AnnualReport doGenerate(Integer year, Long caliberVersionId, String campusScope,
                                   String generatedBy, String triggeredBy, Long jobLogId,
                                   Long revokedOfId) {
        if (year == null) {
            throw new IllegalArgumentException("统计年度不能为空");
        }
        if (generatedBy == null || generatedBy.trim().isEmpty()) {
            throw new IllegalArgumentException("生成人不能为空");
        }
        if (!TRIGGER_MANUAL.equals(triggeredBy) && !TRIGGER_SCHEDULED.equals(triggeredBy)) {
            throw new IllegalArgumentException("触发方式只能为 MANUAL / SCHEDULED");
        }
        AnnualCaliberVersion caliber = resolveCaliber(caliberVersionId);
        AnnualCaliberSpec spec = readSpec(caliber.getCaliberJson());
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime yearStart = LocalDate.of(year, 1, 1).atStartOfDay();
        LocalDateTime nextYearStart = LocalDate.of(year + 1, 1, 1).atStartOfDay();
        LocalDate dayStart = LocalDate.of(year, 1, 1);
        LocalDate dayNextStart = LocalDate.of(year + 1, 1, 1);

        // 批次号：同年度同口径版本内递增（撤回重提领取新批次，旧批次保留）
        Integer maxBatch = reportMapper.selectList(new LambdaQueryWrapper<AnnualReport>()
                        .eq(AnnualReport::getReportYear, year)
                        .eq(AnnualReport::getCaliberVersionId, caliber.getId())
                        .orderByDesc(AnnualReport::getBatchNo).last("LIMIT 1"))
                .stream().findFirst().map(AnnualReport::getBatchNo).orElse(0);
        int batchNo = maxBatch + 1;

        GenerationResult result = collectStatistics(
                spec, yearStart, nextYearStart, dayStart, dayNextStart, campusScope, now);

        AnnualReport report = new AnnualReport();
        report.setReportNo(String.format("YR-%d-%06d", year, reportMapper.nextReportSeq()));
        report.setReportYear(year);
        report.setBatchNo(batchNo);
        report.setCaliberVersionId(caliber.getId());
        report.setCaliberVersionNo(caliber.getVersionNo());
        report.setProjectCount(result.projectCount);
        report.setCompletedCount(result.completedCount);
        report.setMaterialConsumption(result.materialConsumption);
        report.setLoanCount(result.loanLines.size());
        report.setAnomalyCount(result.anomalies.size());
        report.setPendingSupplementCount(result.pendingSupplementCount);
        report.setCampusScope(campusScope);
        report.setGeneratedBy(generatedBy);
        report.setGeneratedAt(now);
        report.setTriggeredBy(triggeredBy);
        report.setJobLogId(jobLogId);
        report.setRevokedOfId(revokedOfId);
        report.setStatus(ANNUAL_REPORT_GENERATED);
        reportMapper.insert(report);

        for (AnnualReportProjectLine line : result.projectLines) {
            line.setReportId(report.getId());
            projectLineMapper.insert(line);
        }
        for (AnnualReportLoanLine line : result.loanLines) {
            line.setReportId(report.getId());
            loanLineMapper.insert(line);
        }
        for (AnnualReportMaterialLine line : result.materialLines) {
            line.setReportId(report.getId());
            materialLineMapper.insert(line);
        }
        for (AnnualReportAnomaly anomaly : result.anomalies) {
            anomaly.setReportId(report.getId());
            anomaly.setVoucherSeq(1);
            anomaly.setAppendedBy(generatedBy);
            anomaly.setAppendedAt(now);
            anomalyMapper.insert(anomaly);
        }

        ledgerService.append(report.getId(), caliber.getId(), ACTION_ANNUAL_REPORT_GENERATE,
                report.getReportNo(),
                "归档年度报表 " + report.getReportNo() + "（年度 " + year + "，第 " + batchNo
                        + " 批，口径 V" + caliber.getVersionNo() + "，" + triggeredBy
                        + (revokedOfId != null ? "，撤回重提批次" : "")
                        + "）：立项 " + report.getProjectCount() + "、完成 " + report.getCompletedCount()
                        + "、材料净消耗 " + report.getMaterialConsumption() + "、借展 "
                        + report.getLoanCount() + "、异常 " + report.getAnomalyCount()
                        + "、待补 " + report.getPendingSupplementCount(), now);

        // 撤回重提：在同一事务内追加可辨台账（旧批次编号/口径/时间与新批次并列留痕）
        if (revokedOfId != null) {
            AnnualReport origin = reportMapper.selectById(revokedOfId);
            if (origin != null) {
                ledgerService.append(report.getId(), caliber.getId(),
                        ACTION_ANNUAL_REPORT_RESUBMIT, report.getReportNo(),
                        "撤回后重提：新报表 " + report.getReportNo() + "（第 " + report.getBatchNo()
                                + " 批，口径 V" + report.getCaliberVersionNo() + "，生成时间 "
                                + report.getGeneratedAt() + "）源自已撤回的 " + origin.getReportNo()
                                + "（口径 V" + origin.getCaliberVersionNo() + "，原生成时间 "
                                + origin.getGeneratedAt() + "）", now);
            }
        }
        return report;
    }

    private static class GenerationResult {
        int projectCount;
        int completedCount;
        int pendingSupplementCount;
        BigDecimal materialConsumption = BigDecimal.ZERO;
        List<AnnualReportProjectLine> projectLines = new ArrayList<>();
        List<AnnualReportLoanLine> loanLines = new ArrayList<>();
        List<AnnualReportMaterialLine> materialLines = new ArrayList<>();
        List<AnnualReportAnomaly> anomalies = new ArrayList<>();
    }

    /** 按绑定口径版本采集五项指标（口径时间列走白名单，时间区间全部参数化）。 */
    private GenerationResult collectStatistics(AnnualCaliberSpec spec,
                                               LocalDateTime yearStart, LocalDateTime nextYearStart,
                                               LocalDate dayStart, LocalDate dayNextStart,
                                               String campusScope, LocalDateTime now) {
        GenerationResult r = new GenerationResult();

        // ---- 立项 + 完成（各自按口径时间字段查询，取并集后逐行标记，支持跨年分别计入） ----
        List<RepairProject> projHits = queryProjectsByDate(spec.getProject().getDateField(),
                spec.getProject().getStatuses(), yearStart, nextYearStart, dayStart, dayNextStart);
        List<RepairProject> compHits = queryProjectsByDate(spec.getCompleted().getDateField(),
                spec.getCompleted().getStatuses(), yearStart, nextYearStart, dayStart, dayNextStart);
        Set<Long> projIds = projHits.stream().map(RepairProject::getId).collect(Collectors.toSet());
        Set<Long> compIds = compHits.stream().map(RepairProject::getId).collect(Collectors.toSet());
        Map<Long, RepairProject> union = new LinkedHashMap<>();
        projHits.forEach(p -> union.put(p.getId(), p));
        compHits.forEach(p -> union.put(p.getId(), p));

        Map<Long, Relic> relicCache = loadRelics(union.values().stream()
                .map(RepairProject::getRelicId).collect(Collectors.toSet()));

        for (RepairProject p : union.values()) {
            Relic relic = relicCache.get(p.getRelicId());
            if (!inScope(relic, campusScope)) {
                continue;
            }
            boolean countedProject = projIds.contains(p.getId())
                    && spec.getProject().getStatuses().contains(p.getStatus());
            boolean countedCompleted = compIds.contains(p.getId())
                    && spec.getCompleted().getStatuses().contains(p.getStatus());
            if (!countedProject && !countedCompleted) {
                continue;
            }
            boolean accepted = acceptanceMapper.selectCount(new LambdaQueryWrapper<RepairAcceptance>()
                    .eq(RepairAcceptance::getProjectId, p.getId())) > 0;
            boolean hasDocs = acceptReportMapper.selectCount(new LambdaQueryWrapper<AcceptReport>()
                    .eq(AcceptReport::getProjectId, p.getId())
                    .eq(AcceptReport::getStatus, AREPORT_PASSED)) > 0;
            boolean pending = countedCompleted && spec.isRequireAcceptanceDocs() && !hasDocs;

            AnnualReportProjectLine line = new AnnualReportProjectLine();
            line.setProjectId(p.getId());
            line.setProjectNo(p.getProjectNo());
            line.setRelicId(relic.getId());
            line.setRelicNo(relic.getRelicNo());
            line.setRelicName(relic.getName());
            line.setCampusCode(relic.getCampusCode());
            line.setSecretLevel(relic.getPreservationGrade());
            line.setProjectStatus(p.getStatus());
            line.setClient(p.getClient());
            line.setCountedProject(countedProject ? 1 : 0);
            line.setCountedCompleted(countedCompleted ? 1 : 0);
            line.setAccepted(accepted ? 1 : 0);
            line.setHasAcceptDocs(hasDocs ? 1 : 0);
            line.setPendingSupplement(pending ? 1 : 0);
            line.setCreatedAt(now);
            r.projectLines.add(line);
            if (countedProject) {
                r.projectCount++;
            }
            if (countedCompleted) {
                r.completedCount++;
            }
            if (pending) {
                r.pendingSupplementCount++;
            }
        }

        // ---- 材料净消耗（实际领用 - 退料） ----
        List<MaterialUsage> usages = usageMapper.selectList(new LambdaQueryWrapper<MaterialUsage>()
                .ge(MaterialUsage::getUsageAt, yearStart)
                .lt(MaterialUsage::getUsageAt, nextYearStart)
                .orderByAsc(MaterialUsage::getId));
        Map<Long, Relic> usageRelics = loadRelics(usages.stream().map(MaterialUsage::getRelicId)
                .collect(Collectors.toSet()));
        usages = usages.stream()
                .filter(u -> inScope(usageRelics.get(u.getRelicId()), campusScope))
                .collect(Collectors.toList());
        if (!usages.isEmpty()) {
            List<Long> usageIds = usages.stream().map(MaterialUsage::getId).collect(Collectors.toList());
            List<MaterialUsageLine> usageLines = usageLineMapper.selectList(
                    new LambdaQueryWrapper<MaterialUsageLine>().in(MaterialUsageLine::getUsageId, usageIds));
            Map<Long, BigDecimal> issuedMap = new HashMap<>();
            for (MaterialUsageLine ul : usageLines) {
                issuedMap.merge(ul.getUsageId(), ul.getActualQty(), BigDecimal::add);
            }
            Map<Long, BigDecimal> returnedMap = new HashMap<>();
            for (MaterialReturn mr : returnMapper.selectList(new LambdaQueryWrapper<MaterialReturn>()
                    .in(MaterialReturn::getUsageId, usageIds))) {
                returnedMap.merge(mr.getUsageId(), mr.getReturnQty(), BigDecimal::add);
            }
            Map<Long, RepairProject> projectCache = loadProjects(
                    usages.stream().map(MaterialUsage::getProjectId).collect(Collectors.toSet()));
            for (MaterialUsage u : usages) {
                BigDecimal issued = issuedMap.getOrDefault(u.getId(), BigDecimal.ZERO);
                BigDecimal returned = returnedMap.getOrDefault(u.getId(), BigDecimal.ZERO);
                BigDecimal net = spec.getMaterial().isDeductReturn()
                        ? issued.subtract(returned) : issued;
                if (net.signum() < 0) {
                    net = BigDecimal.ZERO;
                }
                r.materialConsumption = r.materialConsumption.add(net);
                Relic relic = usageRelics.get(u.getRelicId());
                RepairProject p = projectCache.get(u.getProjectId());
                AnnualReportMaterialLine line = new AnnualReportMaterialLine();
                line.setUsageId(u.getId());
                line.setUsageNo(u.getUsageNo());
                line.setProjectId(u.getProjectId());
                line.setProjectNo(p == null ? "" : p.getProjectNo());
                line.setRelicId(relic.getId());
                line.setRelicNo(relic.getRelicNo());
                line.setRelicName(relic.getName());
                line.setCampusCode(relic.getCampusCode());
                line.setSecretLevel(relic.getPreservationGrade());
                line.setIssuedQty(issued);
                line.setReturnedQty(returned);
                line.setNetQty(net);
                line.setUsageAt(u.getUsageAt());
                line.setCreatedAt(now);
                r.materialLines.add(line);
            }
        }

        // ---- 借展次数：协议版本签署（每次签署算一次借展，变更重签各计一次） ----
        List<LoanAgreementVersion> versions = agreementVersionMapper.selectList(
                new LambdaQueryWrapper<LoanAgreementVersion>()
                        .eq(LoanAgreementVersion::getStatus, AGR_VERSION_SIGNED)
                        .ge(LoanAgreementVersion::getSignedAt, yearStart)
                        .lt(LoanAgreementVersion::getSignedAt, nextYearStart)
                        .orderByAsc(LoanAgreementVersion::getId));
        Map<Long, LoanAgreement> agreementMap = new HashMap<>();
        if (!versions.isEmpty()) {
            List<Long> agreementIds = versions.stream().map(LoanAgreementVersion::getAgreementId)
                    .distinct().collect(Collectors.toList());
            for (LoanAgreement ag : loanAgreementMapper.selectBatchIds(agreementIds)) {
                agreementMap.put(ag.getId(), ag);
            }
        }
        Map<Long, Relic> loanRelics = loadRelics(agreementMap.values().stream()
                .map(LoanAgreement::getRelicId).collect(Collectors.toSet()));
        for (LoanAgreementVersion v : versions) {
            LoanAgreement agreement = agreementMap.get(v.getAgreementId());
            Relic relic = agreement == null ? null : loanRelics.get(agreement.getRelicId());
            if (relic == null || !inScope(relic, campusScope)) {
                continue;
            }
            AnnualReportLoanLine line = new AnnualReportLoanLine();
            line.setAgreementId(v.getAgreementId());
            line.setAgreementNo(agreement.getAgreementNo());
            line.setAgreementVersionId(v.getId());
            line.setVersionNo(v.getVersionNo());
            line.setRelicId(relic.getId());
            line.setRelicNo(relic.getRelicNo());
            line.setRelicName(relic.getName());
            line.setCampusCode(relic.getCampusCode());
            line.setSecretLevel(relic.getPreservationGrade());
            line.setBorrower(v.getBorrower());
            line.setSignedBy(v.getSignedBy());
            line.setSignedAt(v.getSignedAt());
            line.setCreatedAt(now);
            r.loanLines.add(line);
        }

        // ---- 异常事件：环境异常 + 修复设备故障（按口径类型与归属时间字段） ----
        Map<Long, HallInfo> hallInfoCache = new HashMap<>();
        if (spec.getAnomaly().getTypes().contains(ANOMALY_ENV_EVENT)) {
            boolean byFirst = "FIRST_AT".equals(spec.getAnomaly().getEnvDateField());
            LambdaQueryWrapper<EnvEvent> w = new LambdaQueryWrapper<EnvEvent>()
                    .orderByAsc(EnvEvent::getId);
            if (byFirst) {
                w.ge(EnvEvent::getFirstAt, yearStart).lt(EnvEvent::getFirstAt, nextYearStart);
            } else {
                w.ge(EnvEvent::getLastAt, yearStart).lt(EnvEvent::getLastAt, nextYearStart);
            }
            for (EnvEvent e : envEventMapper.selectList(w)) {
                HallInfo info = hallInfo(e.getHallId(), hallInfoCache);
                if (!inScope(info.campus, campusScope)) {
                    continue;
                }
                AnnualReportAnomaly a = new AnnualReportAnomaly();
                a.setAnomalyType(ANOMALY_ENV_EVENT);
                a.setSourceId(e.getId());
                a.setSourceNo(e.getEventNo());
                a.setCampusCode(info.campus);
                a.setSecretLevel(info.secret);
                a.setTitle("展厅环境异常 " + e.getEventNo() + "（" + e.getMetric() + "）");
                a.setDetail("指标 " + e.getMetric() + " 连续超限，峰值 " + e.getPeakValue()
                        + "，状态 " + e.getStatus());
                a.setOccurredAt(byFirst ? e.getFirstAt() : e.getLastAt());
                r.anomalies.add(a);
            }
        }
        if (spec.getAnomaly().getTypes().contains(ANOMALY_EQUIPMENT_FAULT)) {
            List<RepairEquipmentFault> faults = faultMapper.selectList(
                    new LambdaQueryWrapper<RepairEquipmentFault>()
                            .ge(RepairEquipmentFault::getFaultAt, yearStart)
                            .lt(RepairEquipmentFault::getFaultAt, nextYearStart)
                            .orderByAsc(RepairEquipmentFault::getId));
            Map<Long, EquipmentScope> equipScopes = resolveEquipmentScope(faults);
            for (RepairEquipmentFault f : faults) {
                EquipmentScope scope = equipScopes.get(f.getEquipmentId());
                String campus = scope == null ? "UNKNOWN" : scope.campus;
                if (!inScope(campus, campusScope)) {
                    continue;
                }
                AnnualReportAnomaly a = new AnnualReportAnomaly();
                a.setAnomalyType(ANOMALY_EQUIPMENT_FAULT);
                a.setSourceId(f.getId());
                a.setSourceNo(f.getFaultNo());
                a.setCampusCode(campus);
                a.setSecretLevel(scope == null ? GRADE_THREE : scope.secret);
                a.setTitle("修复设备故障 " + f.getFaultNo());
                a.setDetail(f.getDescription());
                a.setOccurredAt(f.getFaultAt());
                r.anomalies.add(a);
            }
        }
        return r;
    }

    /** 按口径时间列查询项目：CREATE_TIME 用时间戳边界，FINISH_DATE 用日期边界。 */
    private List<RepairProject> queryProjectsByDate(String field, List<String> statuses,
                                                    LocalDateTime yearStart, LocalDateTime nextYearStart,
                                                    LocalDate dayStart, LocalDate dayNextStart) {
        LambdaQueryWrapper<RepairProject> w = new LambdaQueryWrapper<RepairProject>()
                .in(RepairProject::getStatus, statuses);
        if ("CREATE_TIME".equals(field)) {
            w.ge(RepairProject::getCreateTime, yearStart)
                    .lt(RepairProject::getCreateTime, nextYearStart);
        } else {
            w.ge(RepairProject::getFinishDate, dayStart)
                    .lt(RepairProject::getFinishDate, dayNextStart);
        }
        return projectMapper.selectList(w);
    }

    private Map<Long, Relic> loadRelics(Set<Long> relicIds) {
        Map<Long, Relic> map = new HashMap<>();
        if (relicIds.isEmpty()) {
            return map;
        }
        for (Relic relic : relicMapper.selectBatchIds(relicIds)) {
            map.put(relic.getId(), relic);
        }
        return map;
    }

    private Map<Long, RepairProject> loadProjects(Set<Long> projectIds) {
        Map<Long, RepairProject> map = new HashMap<>();
        if (projectIds.isEmpty()) {
            return map;
        }
        for (RepairProject p : projectMapper.selectBatchIds(projectIds)) {
            map.put(p.getId(), p);
        }
        return map;
    }

    private boolean inScope(Relic relic, String campusScope) {
        return relic != null && inScope(relic.getCampusCode(), campusScope);
    }

    private boolean inScope(String campusCode, String campusScope) {
        return campusScope == null || campusScope.trim().isEmpty()
                || campusScope.equals(campusCode);
    }

    /** 展厅馆区与最严保密级别（取在展文物）；无在展文物时归 UNKNOWN/三级。 */
    private HallInfo hallInfo(Long hallId, Map<Long, HallInfo> cache) {
        return cache.computeIfAbsent(hallId, id -> {
            String campus = "UNKNOWN";
            String secret = GRADE_THREE;
            List<HallRelic> rels = hallRelicMapper.selectList(new LambdaQueryWrapper<HallRelic>()
                    .eq(HallRelic::getHallId, id));
            for (HallRelic hr : rels) {
                Relic relic = relicMapper.selectById(hr.getRelicId());
                if (relic != null) {
                    campus = relic.getCampusCode();
                    secret = moreSecret(secret, relic.getPreservationGrade());
                }
            }
            return new HallInfo(campus, secret);
        });
    }

    /** 设备经工时行→工时单→项目→文物反查馆区与最严保密级；查不到不臆造，归 UNKNOWN。 */
    private Map<Long, EquipmentScope> resolveEquipmentScope(List<RepairEquipmentFault> faults) {
        Map<Long, EquipmentScope> result = new HashMap<>();
        if (faults.isEmpty()) {
            return result;
        }
        List<Long> equipmentIds = faults.stream().map(RepairEquipmentFault::getEquipmentId)
                .distinct().collect(Collectors.toList());
        List<RepairTimesheetLine> lines = timesheetLineMapper.selectList(
                new LambdaQueryWrapper<RepairTimesheetLine>()
                        .in(RepairTimesheetLine::getEquipmentId, equipmentIds));
        if (lines.isEmpty()) {
            return result;
        }
        List<Long> timesheetIds = lines.stream().map(RepairTimesheetLine::getTimesheetId)
                .distinct().collect(Collectors.toList());
        Map<Long, Long> sheetProject = new HashMap<>();
        for (RepairTimesheet ts : timesheetMapper.selectBatchIds(timesheetIds)) {
            sheetProject.put(ts.getId(), ts.getProjectId());
        }
        Map<Long, Long> projectRelic = new HashMap<>();
        for (RepairProject p : projectMapper.selectBatchIds(sheetProject.values())) {
            projectRelic.put(p.getId(), p.getRelicId());
        }
        Map<Long, Relic> relics = loadRelics(new HashSet<>(projectRelic.values()));
        Map<Long, TreeSet<String>> campusByEquip = new HashMap<>();
        Map<Long, String> secretByEquip = new HashMap<>();
        for (RepairTimesheetLine line : lines) {
            Long projectId = sheetProject.get(line.getTimesheetId());
            Long relicId = projectId == null ? null : projectRelic.get(projectId);
            Relic relic = relicId == null ? null : relics.get(relicId);
            if (relic != null) {
                campusByEquip.computeIfAbsent(line.getEquipmentId(), k -> new TreeSet<>())
                        .add(relic.getCampusCode());
                secretByEquip.merge(line.getEquipmentId(), relic.getPreservationGrade(), this::moreSecret);
            }
        }
        campusByEquip.forEach((equipId, campuses) ->
                result.put(equipId, new EquipmentScope(campuses.first(), secretByEquip.get(equipId))));
        return result;
    }

    private String moreSecret(String a, String b) {
        return secretRank(a) <= secretRank(b) ? a : b;
    }

    /** 保密级别排序：ONE 最严（数字最小） */
    private int secretRank(String grade) {
        if (GRADE_ONE.equals(grade)) {
            return 1;
        }
        if (GRADE_TWO.equals(grade)) {
            return 2;
        }
        return 3;
    }

    private static class HallInfo {
        final String campus;
        final String secret;

        HallInfo(String campus, String secret) {
            this.campus = campus;
            this.secret = secret;
        }
    }

    private static class EquipmentScope {
        final String campus;
        final String secret;

        EquipmentScope(String campus, String secret) {
            this.campus = campus;
            this.secret = secret;
        }
    }

    // ------------------------------------------------------------------
    // 撤回 / 重提
    // ------------------------------------------------------------------

    /** 撤回已归档报表：只置 REVOKED，快照/明细/凭证全部保留，仍可查。 */
    @org.springframework.transaction.annotation.Transactional
    public AnnualReport revoke(Long reportId, String reason, String revokedBy) {
        AnnualReport report = requireReport(reportId);
        if (!ANNUAL_REPORT_GENERATED.equals(report.getStatus())) {
            throw new IllegalArgumentException("报表 " + report.getReportNo() + " 已撤回，不能重复撤回");
        }
        LocalDateTime now = LocalDateTime.now();
        report.setStatus(ANNUAL_REPORT_REVOKED);
        report.setRevokeReason(reason);
        report.setRevokedBy(revokedBy);
        report.setRevokedAt(now);
        reportMapper.updateById(report);
        ledgerService.append(report.getId(), report.getCaliberVersionId(),
                ACTION_ANNUAL_REPORT_REVOKE, report.getReportNo(),
                "撤回年度报表 " + report.getReportNo() + "，原因：" + reason
                        + "；快照与凭证保留，重提将另出新批次新编号", now);
        return report;
    }

    /**
     * 撤回后重提同一批数据：另出新批次、新编号、新生成时间；
     * 默认沿用原报表绑定的口径版本（显式 caliberVersionId 可指定新版本），
     * 撤回链以 revokedOfId 串联，编号/口径版本/时间三者都可辨。
     */
    public AnnualReport resubmit(Long reportId, Long caliberVersionId, String generatedBy) {
        AnnualReport origin = requireReport(reportId);
        if (!ANNUAL_REPORT_REVOKED.equals(origin.getStatus())) {
            throw new IllegalArgumentException("仅已撤回的报表可以重提：" + origin.getReportNo());
        }
        Long useCaliberId = caliberVersionId != null ? caliberVersionId : origin.getCaliberVersionId();
        // 链字段与重提台账均在生成事务内写入：编号/批次/口径版本/生成时间全部新取，旧批次不动
        return transactionTemplate.execute(s -> doGenerate(
                origin.getReportYear(), useCaliberId, origin.getCampusScope(), generatedBy,
                TRIGGER_MANUAL, null, origin.getId()));
    }

    // ------------------------------------------------------------------
    // 异常凭证只追加
    // ------------------------------------------------------------------

    /**
     * 追加异常凭证：同一异常凭证序号递增，只 INSERT，永不更新/覆盖/删除已有凭证。
     * 仅接受本报表快照已覆盖的异常来源，避免把别的年度/范围的异常塞进归档快照。
     */
    @org.springframework.transaction.annotation.Transactional
    public AnnualReportAnomaly appendAnomaly(Long reportId, String anomalyType, Long sourceId,
                                             String detail, String appendedBy) {
        AnnualReport report = requireReport(reportId);
        if (ANNUAL_REPORT_REVOKED.equals(report.getStatus())) {
            throw new IllegalArgumentException("报表 " + report.getReportNo() + " 已撤回，不能再追加凭证");
        }
        if (!ANOMALY_ENV_EVENT.equals(anomalyType) && !ANOMALY_EQUIPMENT_FAULT.equals(anomalyType)) {
            throw new IllegalArgumentException("异常类型只能为 ENV_EVENT / EQUIPMENT_FAULT");
        }
        List<AnnualReportAnomaly> existing = anomalyMapper.selectList(
                new LambdaQueryWrapper<AnnualReportAnomaly>()
                        .eq(AnnualReportAnomaly::getReportId, reportId)
                        .eq(AnnualReportAnomaly::getAnomalyType, anomalyType)
                        .eq(AnnualReportAnomaly::getSourceId, sourceId)
                        .orderByAsc(AnnualReportAnomaly::getVoucherSeq));
        if (existing.isEmpty()) {
            throw new IllegalArgumentException("异常来源不在报表 " + report.getReportNo()
                    + " 的快照范围内，不能追加凭证；请重新生成新批次报表");
        }
        AnnualReportAnomaly first = existing.get(0);
        int nextSeq = existing.stream().mapToInt(AnnualReportAnomaly::getVoucherSeq).max().orElse(0) + 1;
        LocalDateTime now = LocalDateTime.now();

        AnnualReportAnomaly voucher = new AnnualReportAnomaly();
        voucher.setReportId(reportId);
        voucher.setAnomalyType(anomalyType);
        voucher.setSourceId(sourceId);
        voucher.setSourceNo(first.getSourceNo());
        voucher.setCampusCode(first.getCampusCode());
        voucher.setSecretLevel(first.getSecretLevel());
        voucher.setTitle(first.getTitle());
        voucher.setDetail(detail);
        voucher.setOccurredAt(first.getOccurredAt());
        voucher.setVoucherSeq(nextSeq);
        voucher.setAppendedBy(appendedBy);
        voucher.setAppendedAt(now);
        anomalyMapper.insert(voucher);

        ledgerService.append(reportId, report.getCaliberVersionId(), ACTION_ANNUAL_ANOMALY_APPEND,
                report.getReportNo(),
                "报表 " + report.getReportNo() + " 追加异常凭证 " + first.getSourceNo() + " 第 "
                        + nextSeq + " 份（只追加，已有凭证不覆盖）", now);
        return voucher;
    }

    // ------------------------------------------------------------------
    // 查询 / 告警 / 任务日志
    // ------------------------------------------------------------------

    public AnnualReport requireReport(Long reportId) {
        AnnualReport report = reportMapper.selectById(reportId);
        if (report == null) {
            throw new IllegalArgumentException("年度报表不存在: id=" + reportId);
        }
        return report;
    }

    public List<AnnualReport> listReports(Integer year) {
        LambdaQueryWrapper<AnnualReport> wrapper = new LambdaQueryWrapper<AnnualReport>()
                .orderByDesc(AnnualReport::getReportYear)
                .orderByAsc(AnnualReport::getCaliberVersionNo)
                .orderByDesc(AnnualReport::getBatchNo)
                .orderByDesc(AnnualReport::getId);
        if (year != null) {
            wrapper.eq(AnnualReport::getReportYear, year);
        }
        return reportMapper.selectList(wrapper);
    }

    /** 报表详情：头 + 口径版本（含 JSON 规格）+ 各类明细 + 待补项目。 */
    public Map<String, Object> detail(Long reportId) {
        AnnualReport report = requireReport(reportId);
        AnnualCaliberVersion caliber = caliberMapper.selectById(report.getCaliberVersionId());
        List<AnnualReportProjectLine> projectLines = projectLineMapper.selectList(
                new LambdaQueryWrapper<AnnualReportProjectLine>()
                        .eq(AnnualReportProjectLine::getReportId, reportId)
                        .orderByAsc(AnnualReportProjectLine::getId));
        List<AnnualReportLoanLine> loanLines = loanLineMapper.selectList(
                new LambdaQueryWrapper<AnnualReportLoanLine>()
                        .eq(AnnualReportLoanLine::getReportId, reportId)
                        .orderByAsc(AnnualReportLoanLine::getId));
        List<AnnualReportMaterialLine> materialLines = materialLineMapper.selectList(
                new LambdaQueryWrapper<AnnualReportMaterialLine>()
                        .eq(AnnualReportMaterialLine::getReportId, reportId)
                        .orderByAsc(AnnualReportMaterialLine::getId));
        List<AnnualReportAnomaly> anomalies = anomalyMapper.selectList(
                new LambdaQueryWrapper<AnnualReportAnomaly>()
                        .eq(AnnualReportAnomaly::getReportId, reportId)
                        .orderByAsc(AnnualReportAnomaly::getAnomalyType)
                        .orderByAsc(AnnualReportAnomaly::getSourceId)
                        .orderByAsc(AnnualReportAnomaly::getVoucherSeq));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("report", report);
        result.put("caliberVersion", caliber);
        result.put("caliberSpec", caliber == null ? null : readSpec(caliber.getCaliberJson()));
        result.put("projectLines", projectLines);
        result.put("loanLines", loanLines);
        result.put("materialLines", materialLines);
        result.put("anomalies", anomalies);
        result.put("pendingSupplements", projectLines.stream()
                .filter(l -> l.getPendingSupplement() != null && l.getPendingSupplement() == 1)
                .collect(Collectors.toList()));
        return result;
    }

    public List<AnnualReportAlert> listAlerts(String status) {
        LambdaQueryWrapper<AnnualReportAlert> wrapper = new LambdaQueryWrapper<AnnualReportAlert>()
                .orderByDesc(AnnualReportAlert::getId);
        if (status != null && !status.trim().isEmpty()) {
            wrapper.eq(AnnualReportAlert::getStatus, status);
        }
        return alertMapper.selectList(wrapper);
    }

    /** 确认失败告警：OPEN -> ACKNOWLEDGED，只追加台账，不改写失败日志。 */
    @org.springframework.transaction.annotation.Transactional
    public AnnualReportAlert acknowledgeAlert(Long alertId, String acknowledgedBy, String note) {
        AnnualReportAlert alert = alertMapper.selectById(alertId);
        if (alert == null) {
            throw new IllegalArgumentException("告警不存在: id=" + alertId);
        }
        if (ANNUAL_ALERT_ACKED.equals(alert.getStatus())) {
            throw new IllegalArgumentException("告警 " + alert.getAlertNo() + " 已确认");
        }
        LocalDateTime now = LocalDateTime.now();
        alert.setStatus(ANNUAL_ALERT_ACKED);
        alert.setAcknowledgedBy(acknowledgedBy);
        alert.setAcknowledgedAt(now);
        alert.setAckNote(note);
        alertMapper.updateById(alert);
        ledgerService.append(null, null, ACTION_ANNUAL_ALERT_ACK, alert.getAlertNo(),
                "确认年度报表生成失败告警 " + alert.getAlertNo() + "：" + (note == null ? "" : note), now);
        return alert;
    }

    public List<AnnualReportJobLog> listJobLogs(Integer year) {
        LambdaQueryWrapper<AnnualReportJobLog> wrapper = new LambdaQueryWrapper<AnnualReportJobLog>()
                .orderByDesc(AnnualReportJobLog::getId);
        if (year != null) {
            wrapper.eq(AnnualReportJobLog::getReportYear, year);
        }
        return jobLogMapper.selectList(wrapper);
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    // ------------------------------------------------------------------
    // 导出：按馆区 + 保密级别裁剪字段
    // ------------------------------------------------------------------

    /**
     * CSV 导出：先按馆区整行过滤（非本馆区不出库），再按导出人保密许可裁剪字段：
     * REPORT_ADMIN 见全部密级；REPORT_READER（默认）对保密一级文物剔除敏感字段
     * （编号/名称/委托人/借展方/凭证内容等置为保密占位），统计事实保留。
     */
    public String exportCsv(Long reportId, String viewerCampus, String role) {
        if (viewerCampus == null || viewerCampus.trim().isEmpty()) {
            throw new IllegalArgumentException("导出必须声明导出人馆区，按馆区裁剪后出库");
        }
        AnnualReport report = requireReport(reportId);
        boolean admin = ROLE_REPORT_ADMIN.equals(role);
        String effectiveRole = admin ? ROLE_REPORT_ADMIN : ROLE_REPORT_READER;

        List<AnnualReportProjectLine> projects = projectLineMapper.selectList(
                        new LambdaQueryWrapper<AnnualReportProjectLine>()
                                .eq(AnnualReportProjectLine::getReportId, reportId)
                                .orderByAsc(AnnualReportProjectLine::getId)).stream()
                .filter(l -> viewerCampus.equals(l.getCampusCode())).collect(Collectors.toList());
        List<AnnualReportLoanLine> loans = loanLineMapper.selectList(
                        new LambdaQueryWrapper<AnnualReportLoanLine>()
                                .eq(AnnualReportLoanLine::getReportId, reportId)
                                .orderByAsc(AnnualReportLoanLine::getId)).stream()
                .filter(l -> viewerCampus.equals(l.getCampusCode())).collect(Collectors.toList());
        List<AnnualReportMaterialLine> materials = materialLineMapper.selectList(
                        new LambdaQueryWrapper<AnnualReportMaterialLine>()
                                .eq(AnnualReportMaterialLine::getReportId, reportId)
                                .orderByAsc(AnnualReportMaterialLine::getId)).stream()
                .filter(l -> viewerCampus.equals(l.getCampusCode())).collect(Collectors.toList());
        List<AnnualReportAnomaly> anomalies = anomalyMapper.selectList(
                        new LambdaQueryWrapper<AnnualReportAnomaly>()
                                .eq(AnnualReportAnomaly::getReportId, reportId)
                                .orderByAsc(AnnualReportAnomaly::getAnomalyType)
                                .orderByAsc(AnnualReportAnomaly::getSourceId)
                                .orderByAsc(AnnualReportAnomaly::getVoucherSeq)).stream()
                .filter(l -> viewerCampus.equals(l.getCampusCode())).collect(Collectors.toList());

        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append("# 文物修复年度报表（归档不可变快照）\n");
        csv.append("报表编号,").append(csvCell(report.getReportNo())).append('\n');
        csv.append("统计年度,").append(report.getReportYear()).append('\n');
        csv.append("批次号,").append(report.getBatchNo()).append('\n');
        csv.append("口径版本,V").append(report.getCaliberVersionNo()).append('\n');
        csv.append("归档馆区范围,").append(csvCell(report.getCampusScope() == null ? "全馆区" : report.getCampusScope())).append('\n');
        csv.append("导出馆区,").append(csvCell(viewerCampus)).append('\n');
        csv.append("导出角色,").append(effectiveRole).append('\n');
        csv.append("生成时间,").append(csvCell(report.getGeneratedAt() == null ? "" : report.getGeneratedAt().toString())).append('\n');
        csv.append("状态,").append(csvCell(report.getStatus())).append('\n');
        csv.append("归档合计,立项数=").append(report.getProjectCount())
                .append("；完成数=").append(report.getCompletedCount())
                .append("；材料净消耗=").append(report.getMaterialConsumption())
                .append("；借展次数=").append(report.getLoanCount())
                .append("；异常事件=").append(report.getAnomalyCount())
                .append("；待补=").append(report.getPendingSupplementCount())
                .append('\n');
        csv.append("导出馆区可见合计,立项数=").append(projects.stream().mapToInt(AnnualReportProjectLine::getCountedProject).sum())
                .append("；完成数=").append(projects.stream().mapToInt(AnnualReportProjectLine::getCountedCompleted).sum())
                .append("；待补=").append(projects.stream().mapToInt(AnnualReportProjectLine::getPendingSupplement).sum())
                .append("；借展=").append(loans.size())
                .append("；材料净消耗=").append(materials.stream().map(AnnualReportMaterialLine::getNetQty)
                        .reduce(BigDecimal.ZERO, BigDecimal::add))
                .append("；异常凭证=").append(anomalies.size())
                .append('\n');

        csv.append("\n[项目明细]\n");
        csv.append("项目编号,文物编号,文物名称,保密级别,项目状态,委托人,计入立项,计入完成,已验收,验收资料齐备,待补\n");
        for (AnnualReportProjectLine l : projects) {
            boolean masked = !admin && GRADE_ONE.equals(l.getSecretLevel());
            csv.append(csvCell(l.getProjectNo())).append(',')
                    .append(csvCell(masked ? "***" : l.getRelicNo())).append(',')
                    .append(csvCell(masked ? "***保密一级***" : l.getRelicName())).append(',')
                    .append(csvCell(l.getSecretLevel())).append(',')
                    .append(csvCell(l.getProjectStatus())).append(',')
                    .append(csvCell(masked ? "***" : l.getClient())).append(',')
                    .append(l.getCountedProject()).append(',')
                    .append(l.getCountedCompleted()).append(',')
                    .append(l.getAccepted()).append(',')
                    .append(l.getHasAcceptDocs()).append(',')
                    .append(l.getPendingSupplement()).append('\n');
        }

        csv.append("\n[借展明细]\n");
        csv.append("协议编号,协议版本,文物编号,文物名称,保密级别,借展方,签署人,签署时间\n");
        for (AnnualReportLoanLine l : loans) {
            boolean masked = !admin && GRADE_ONE.equals(l.getSecretLevel());
            csv.append(csvCell(l.getAgreementNo())).append(',')
                    .append('V').append(l.getVersionNo()).append(',')
                    .append(csvCell(masked ? "***" : l.getRelicNo())).append(',')
                    .append(csvCell(masked ? "***保密一级***" : l.getRelicName())).append(',')
                    .append(csvCell(l.getSecretLevel())).append(',')
                    .append(csvCell(masked ? "***" : l.getBorrower())).append(',')
                    .append(csvCell(l.getSignedBy())).append(',')
                    .append(csvCell(l.getSignedAt() == null ? "" : l.getSignedAt().toString())).append('\n');
        }

        csv.append("\n[材料消耗明细]\n");
        csv.append("领用单号,项目编号,文物编号,文物名称,保密级别,领用量,退料量,净消耗,领用时间\n");
        for (AnnualReportMaterialLine l : materials) {
            boolean masked = !admin && GRADE_ONE.equals(l.getSecretLevel());
            csv.append(csvCell(l.getUsageNo())).append(',')
                    .append(csvCell(l.getProjectNo())).append(',')
                    .append(csvCell(masked ? "***" : l.getRelicNo())).append(',')
                    .append(csvCell(masked ? "***保密一级***" : l.getRelicName())).append(',')
                    .append(csvCell(l.getSecretLevel())).append(',')
                    .append(l.getIssuedQty()).append(',')
                    .append(l.getReturnedQty()).append(',')
                    .append(l.getNetQty()).append(',')
                    .append(csvCell(l.getUsageAt() == null ? "" : l.getUsageAt().toString())).append('\n');
        }

        csv.append("\n[异常凭证（只追加）]\n");
        csv.append("异常类型,来源单号,凭证序号,标题,凭证内容,发生时间,追加人,追加时间\n");
        for (AnnualReportAnomaly l : anomalies) {
            boolean masked = !admin && GRADE_ONE.equals(l.getSecretLevel());
            csv.append(csvCell(l.getAnomalyType())).append(',')
                    .append(csvCell(l.getSourceNo())).append(',')
                    .append(l.getVoucherSeq()).append(',')
                    .append(csvCell(masked ? "***保密一级***" : l.getTitle())).append(',')
                    .append(csvCell(masked ? "***" : l.getDetail())).append(',')
                    .append(csvCell(l.getOccurredAt() == null ? "" : l.getOccurredAt().toString())).append(',')
                    .append(csvCell(l.getAppendedBy())).append(',')
                    .append(csvCell(l.getAppendedAt() == null ? "" : l.getAppendedAt().toString())).append('\n');
        }
        return csv.toString();
    }

    private String csvCell(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
