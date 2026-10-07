package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.config.MuseumAcceptanceProperties;
import com.evops.dto.AcceptConfirmRequest;
import com.evops.dto.AcceptReportSubmitRequest;
import com.evops.entity.AcceptCheckItem;
import com.evops.entity.AcceptReport;
import com.evops.entity.Relic;
import com.evops.entity.RepairProject;
import com.evops.mapper.AcceptCheckItemMapper;
import com.evops.mapper.AcceptReportMapper;
import com.evops.mapper.RelicMapper;
import com.evops.mapper.RepairProjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.evops.constant.MuseumConstants.*;

/**
 * 修复成果验收：
 *   修复师提交成果报告（按文物材质+工艺生成验收清单，轮次项目内递增）
 *   -> 馆内专家、保管员分别逐项确认（不能代签）
 *   -> 任一项缺项即整改（RECTIFICATION），整改后重新提交新一轮报告
 *   -> 双方全部合格则验收通过（PASSED，终态）。
 * 报告与清单只追加不改写；确认结果固化在清单项上。
 */
@Service
public class AcceptanceReviewService {

    private static final DateTimeFormatter CSV_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AcceptReportMapper reportMapper;
    private final AcceptCheckItemMapper itemMapper;
    private final RepairProjectMapper projectMapper;
    private final RelicMapper relicMapper;
    private final RelicService relicService;
    private final LedgerService ledgerService;
    private final MuseumAcceptanceProperties acceptanceProperties;

    public AcceptanceReviewService(AcceptReportMapper reportMapper,
                                   AcceptCheckItemMapper itemMapper,
                                   RepairProjectMapper projectMapper,
                                   RelicMapper relicMapper,
                                   RelicService relicService,
                                   LedgerService ledgerService,
                                   MuseumAcceptanceProperties acceptanceProperties) {
        this.reportMapper = reportMapper;
        this.itemMapper = itemMapper;
        this.projectMapper = projectMapper;
        this.relicMapper = relicMapper;
        this.relicService = relicService;
        this.ledgerService = ledgerService;
        this.acceptanceProperties = acceptanceProperties;
    }

    /**
     * 提交成果报告：项目须修复完成（REPAIRED）或已验收闭环（ACCEPTED）；
     * 存在确认中的报告不能重复提交；验收已通过的项目不再接受新报告。
     * 验收清单按文物材质 + 本次工艺从配置模板生成并固化。
     */
    @Transactional
    public AcceptReport submitReport(Long projectId, AcceptReportSubmitRequest req) {
        RepairProject project = requireProject(projectId);
        if (!PROJECT_REPAIRED.equals(project.getStatus())
                && !PROJECT_ACCEPTED.equals(project.getStatus())) {
            throw new IllegalArgumentException("项目未修复完成，不能提交成果报告: " + project.getProjectNo());
        }
        Long inReview = reportMapper.selectCount(new LambdaQueryWrapper<AcceptReport>()
                .eq(AcceptReport::getProjectId, projectId)
                .eq(AcceptReport::getStatus, AREPORT_IN_REVIEW));
        if (inReview != null && inReview > 0) {
            throw new IllegalArgumentException("存在确认中的成果报告，不能重复提交: " + project.getProjectNo());
        }
        Long passed = reportMapper.selectCount(new LambdaQueryWrapper<AcceptReport>()
                .eq(AcceptReport::getProjectId, projectId)
                .eq(AcceptReport::getStatus, AREPORT_PASSED));
        if (passed != null && passed > 0) {
            throw new IllegalArgumentException("成果验收已通过，不再接受新报告: " + project.getProjectNo());
        }

        Relic relic = relicService.requireRelic(project.getRelicId());
        String craft = req.getCraft().trim();
        List<String> template = acceptanceProperties.itemsFor(relic.getMaterial(), craft);

        Integer maxRound = reportMapper.selectList(new LambdaQueryWrapper<AcceptReport>()
                        .eq(AcceptReport::getProjectId, projectId)
                        .orderByDesc(AcceptReport::getRoundNo)
                        .last("LIMIT 1"))
                .stream().findFirst().map(AcceptReport::getRoundNo).orElse(0);

        LocalDateTime now = LocalDateTime.now();
        AcceptReport report = new AcceptReport();
        report.setProjectId(projectId);
        report.setRoundNo(maxRound + 1);
        report.setCraft(craft);
        report.setSummary(req.getSummary().trim());
        report.setExpertName(req.getExpertName().trim());
        report.setCustodianName(req.getCustodianName().trim());
        report.setSubmittedBy(req.getSubmittedBy().trim());
        report.setSubmittedAt(now);
        report.setStatus(AREPORT_IN_REVIEW);
        reportMapper.insert(report);

        int itemNo = 1;
        for (String content : template) {
            AcceptCheckItem item = new AcceptCheckItem();
            item.setReportId(report.getId());
            item.setItemNo(itemNo++);
            item.setContent(content);
            itemMapper.insert(item);
        }

        ledgerService.append(project.getRelicId(), projectId, null,
                ACTION_ACCEPT_REPORT_SUBMIT, project.getProjectNo(),
                "成果报告第" + report.getRoundNo() + "轮提交，工艺【" + craft + "】，验收清单 "
                        + template.size() + " 项（材质" + relic.getMaterial() + "），提交人："
                        + report.getSubmittedBy(), now);
        return report;
    }

    /**
     * 逐项确认：角色须为 INTERNAL（馆内专家）/ CUSTODIAN（保管员），
     * 确认人须为报告指定确认人本人（不能代签）；每角色只确认一次；
     * 逐项结果必须恰好覆盖报告全部清单项。
     * 任一项 MISSING 即整整改；双方全部 OK 则验收通过。
     */
    @Transactional
    public AcceptReport confirm(Long reportId, AcceptConfirmRequest req) {
        AcceptReport report = requireReport(reportId);
        if (!AREPORT_IN_REVIEW.equals(report.getStatus())) {
            throw new IllegalArgumentException("报告第" + report.getRoundNo() + "轮已"
                    + (AREPORT_RECTIFICATION.equals(report.getStatus()) ? "进入整改" : "验收通过")
                    + "，保持只读，整改请重新提交新一轮报告");
        }
        String role = req.getRole() == null ? "" : req.getRole().trim().toUpperCase();
        if (!SIGN_ROLE_INTERNAL.equals(role) && !SIGN_ROLE_CUSTODIAN.equals(role)) {
            throw new IllegalArgumentException("确认角色只能为 INTERNAL（馆内专家）/ CUSTODIAN（保管员）");
        }
        String designated = SIGN_ROLE_INTERNAL.equals(role)
                ? report.getExpertName() : report.getCustodianName();
        if (!designated.equals(req.getConfirmer())) {
            throw new IllegalArgumentException("缺席确认人不能由他人代签：当前应确认人为" + designated
                    + "，实际确认人为" + req.getConfirmer());
        }

        List<AcceptCheckItem> items = listItems(reportId);
        boolean expert = SIGN_ROLE_INTERNAL.equals(role);
        boolean alreadyDone = items.stream().anyMatch(i -> expert
                ? i.getExpertResult() != null : i.getCustodianResult() != null);
        if (alreadyDone) {
            throw new IllegalArgumentException("该角色已确认过本轮报告，不能重复确认");
        }

        Map<Long, String> results = new HashMap<>();
        for (AcceptConfirmRequest.ItemResult line : req.getItems()) {
            if (!ITEM_OK.equals(line.getResult()) && !ITEM_MISSING.equals(line.getResult())) {
                throw new IllegalArgumentException("确认结果只能为 OK/MISSING: " + line.getResult());
            }
            results.put(line.getItemId(), line.getResult());
        }
        Set<Long> itemIds = items.stream().map(AcceptCheckItem::getId).collect(Collectors.toSet());
        if (!results.keySet().equals(itemIds)) {
            throw new IllegalArgumentException("逐项确认必须恰好覆盖全部 " + items.size()
                    + " 个清单项，实际提交 " + results.size() + " 项");
        }

        LocalDateTime now = LocalDateTime.now();
        List<String> missingContents = new ArrayList<>();
        for (AcceptCheckItem item : items) {
            String result = results.get(item.getId());
            if (expert) {
                item.setExpertResult(result);
            } else {
                item.setCustodianResult(result);
            }
            itemMapper.updateById(item);
            if (ITEM_MISSING.equals(result)) {
                missingContents.add(item.getContent());
            }
        }

        RepairProject project = requireProject(report.getProjectId());
        String roleLabel = expert ? "馆内专家" : "保管员";
        ledgerService.append(project.getRelicId(), project.getId(), null,
                ACTION_ACCEPT_CONFIRM, project.getProjectNo(),
                "第" + report.getRoundNo() + "轮报告" + roleLabel + "确认：" + req.getConfirmer()
                        + "，缺项 " + missingContents.size() + " 项"
                        + (req.getOpinion() != null && !req.getOpinion().isEmpty()
                        ? "，意见：" + req.getOpinion() : ""), now);

        // 缺一项即整改：任一角色任一项缺项，报告立即进入整改（不等另一角色）
        boolean anyMissing = items.stream().anyMatch(i ->
                ITEM_MISSING.equals(i.getExpertResult()) || ITEM_MISSING.equals(i.getCustodianResult()));
        if (anyMissing) {
            List<String> allMissing = items.stream()
                    .filter(i -> ITEM_MISSING.equals(i.getExpertResult())
                            || ITEM_MISSING.equals(i.getCustodianResult()))
                    .map(AcceptCheckItem::getContent).collect(Collectors.toList());
            report.setStatus(AREPORT_RECTIFICATION);
            report.setClosedAt(now);
            reportMapper.updateById(report);
            ledgerService.append(project.getRelicId(), project.getId(), null,
                    ACTION_ACCEPT_RECTIFY, project.getProjectNo(),
                    "第" + report.getRoundNo() + "轮验收缺项进入整改，缺项：" + String.join("；", allMissing),
                    now);
            return report;
        }

        boolean expertDone = items.stream().allMatch(i -> ITEM_OK.equals(i.getExpertResult()));
        boolean custodianDone = items.stream().allMatch(i -> ITEM_OK.equals(i.getCustodianResult()));
        if (expertDone && custodianDone) {
            report.setStatus(AREPORT_PASSED);
            report.setClosedAt(now);
            reportMapper.updateById(report);
            ledgerService.append(project.getRelicId(), project.getId(), null,
                    ACTION_ACCEPT_PASS, project.getProjectNo(),
                    "第" + report.getRoundNo() + "轮成果验收通过（馆内专家+保管员双确认全部合格），"
                            + "允许设置开放展示标记", now);
        }
        return report;
    }

    /** 报告详情：报告 + 验收清单（含双方逐项确认结果） */
    public Map<String, Object> getReportDetail(Long reportId) {
        AcceptReport report = requireReport(reportId);
        RepairProject project = requireProject(report.getProjectId());
        Relic relic = relicService.requireRelic(project.getRelicId());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("report", report);
        detail.put("items", listItems(reportId));
        detail.put("projectNo", project.getProjectNo());
        detail.put("relicNo", relic.getRelicNo());
        detail.put("relicName", relic.getName());
        detail.put("material", relic.getMaterial());
        return detail;
    }

    /** 项目报告轮次列表（含各轮清单项） */
    public List<Map<String, Object>> listByProject(Long projectId) {
        requireProject(projectId);
        List<AcceptReport> reports = reportMapper.selectList(new LambdaQueryWrapper<AcceptReport>()
                .eq(AcceptReport::getProjectId, projectId)
                .orderByAsc(AcceptReport::getRoundNo));
        List<Map<String, Object>> result = new ArrayList<>();
        for (AcceptReport report : reports) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("report", report);
            row.put("items", listItems(report.getId()));
            result.add(row);
        }
        return result;
    }

    /**
     * 验收台账列表行：报告 + 项目/文物口径，供列表与 CSV 导出共用同一行集。
     */
    public List<Map<String, Object>> listRows(Long relicId, String status) {
        List<AcceptReport> reports = reportMapper.selectList(new LambdaQueryWrapper<AcceptReport>()
                .eq(status != null && !status.isEmpty(), AcceptReport::getStatus, status)
                .orderByAsc(AcceptReport::getId));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AcceptReport report : reports) {
            RepairProject project = projectMapper.selectById(report.getProjectId());
            if (project == null) {
                continue;
            }
            if (relicId != null && !relicId.equals(project.getRelicId())) {
                continue;
            }
            Relic relic = relicMapper.selectById(project.getRelicId());
            List<AcceptCheckItem> items = listItems(report.getId());
            long missing = items.stream().filter(i ->
                    ITEM_MISSING.equals(i.getExpertResult())
                            || ITEM_MISSING.equals(i.getCustodianResult())).count();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("reportId", report.getId());
            row.put("projectId", project.getId());
            row.put("projectNo", project.getProjectNo());
            row.put("relicId", project.getRelicId());
            row.put("relicNo", relic == null ? null : relic.getRelicNo());
            row.put("relicName", relic == null ? null : relic.getName());
            row.put("material", relic == null ? null : relic.getMaterial());
            row.put("roundNo", report.getRoundNo());
            row.put("craft", report.getCraft());
            row.put("status", report.getStatus());
            row.put("itemCount", items.size());
            row.put("missingCount", missing);
            row.put("expertName", report.getExpertName());
            row.put("custodianName", report.getCustodianName());
            row.put("submittedBy", report.getSubmittedBy());
            row.put("submittedAt", report.getSubmittedAt());
            rows.add(row);
        }
        return rows;
    }

    /**
     * CSV 导出：与列表查询同一行集同一顺序，列口径固定。
     * 带 BOM，便于 Excel 直接打开中文。
     */
    public String exportCsv(Long relicId, String status) {
        List<Map<String, Object>> rows = listRows(relicId, status);
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append("报告ID,项目编号,文物编号,文物名称,材质,轮次,工艺,状态,清单项数,缺项数,"
                + "馆内专家,保管员,提交人,提交时间\n");
        for (Map<String, Object> row : rows) {
            csv.append(row.get("reportId")).append(',')
                    .append(csvCell(row.get("projectNo"))).append(',')
                    .append(csvCell(row.get("relicNo"))).append(',')
                    .append(csvCell(row.get("relicName"))).append(',')
                    .append(csvCell(row.get("material"))).append(',')
                    .append(row.get("roundNo")).append(',')
                    .append(csvCell(row.get("craft"))).append(',')
                    .append(csvCell(row.get("status"))).append(',')
                    .append(row.get("itemCount")).append(',')
                    .append(row.get("missingCount")).append(',')
                    .append(csvCell(row.get("expertName"))).append(',')
                    .append(csvCell(row.get("custodianName"))).append(',')
                    .append(csvCell(row.get("submittedBy"))).append(',')
                    .append(row.get("submittedAt") == null ? ""
                            : ((LocalDateTime) row.get("submittedAt")).format(CSV_TIME))
                    .append('\n');
        }
        return csv.toString();
    }

    public AcceptReport requireReport(Long reportId) {
        AcceptReport report = reportMapper.selectById(reportId);
        if (report == null) {
            throw new IllegalArgumentException("成果报告不存在: id=" + reportId);
        }
        return report;
    }

    public List<AcceptCheckItem> listItems(Long reportId) {
        return itemMapper.selectList(new LambdaQueryWrapper<AcceptCheckItem>()
                .eq(AcceptCheckItem::getReportId, reportId)
                .orderByAsc(AcceptCheckItem::getItemNo));
    }

    /** 项目最新一轮验收通过的报告（开放展示标记的门槛依据） */
    public AcceptReport latestPassedReport(Long projectId) {
        return reportMapper.selectList(new LambdaQueryWrapper<AcceptReport>()
                        .eq(AcceptReport::getProjectId, projectId)
                        .eq(AcceptReport::getStatus, AREPORT_PASSED)
                        .orderByDesc(AcceptReport::getRoundNo)
                        .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
    }

    private RepairProject requireProject(Long projectId) {
        RepairProject project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new IllegalArgumentException("修复项目不存在: id=" + projectId);
        }
        return project;
    }

    private String csvCell(Object value) {
        if (value == null) {
            return "";
        }
        String s = value.toString();
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }
}
