package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.dto.InvRebaselineRequest;
import com.evops.dto.InvScanRequest;
import com.evops.dto.InvSupplementRequest;
import com.evops.dto.InvTaskCloseRequest;
import com.evops.dto.InvTaskGenerateRequest;
import com.evops.entity.Hall;
import com.evops.entity.HallRelic;
import com.evops.entity.InvDiffOrder;
import com.evops.entity.InvScan;
import com.evops.entity.InvSupplement;
import com.evops.entity.InvTask;
import com.evops.entity.InvTaskLine;
import com.evops.entity.LedgerEntry;
import com.evops.entity.Relic;
import com.evops.entity.RelicLoan;
import com.evops.mapper.HallRelicMapper;
import com.evops.mapper.InvDiffOrderMapper;
import com.evops.mapper.InvScanMapper;
import com.evops.mapper.InvSupplementMapper;
import com.evops.mapper.InvTaskLineMapper;
import com.evops.mapper.InvTaskMapper;
import com.evops.mapper.LedgerEntryMapper;
import com.evops.mapper.RelicLoanMapper;
import com.evops.mapper.RelicMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.evops.constant.MuseumConstants.*;

/**
 * 展览借品清点：按展厅生成清点任务，工作人员扫描箱号与展签，
 * 系统列出缺失、错位与状态异常；清点期间发生借还/展陈变更提示版本冲突，
 * 重基线生成新版本并把旧版本扫描逐条回放；已关闭任务只能新增补充记录。
 */
@Service
public class InventoryService {

    private final InvTaskMapper taskMapper;
    private final InvTaskLineMapper lineMapper;
    private final InvScanMapper scanMapper;
    private final InvDiffOrderMapper diffOrderMapper;
    private final InvSupplementMapper supplementMapper;
    private final HallService hallService;
    private final HallRelicMapper hallRelicMapper;
    private final RelicMapper relicMapper;
    private final RelicLoanMapper loanMapper;
    private final LedgerEntryMapper ledgerEntryMapper;
    private final InvLedgerService ledgerService;

    public InventoryService(InvTaskMapper taskMapper,
                            InvTaskLineMapper lineMapper,
                            InvScanMapper scanMapper,
                            InvDiffOrderMapper diffOrderMapper,
                            InvSupplementMapper supplementMapper,
                            HallService hallService,
                            HallRelicMapper hallRelicMapper,
                            RelicMapper relicMapper,
                            RelicLoanMapper loanMapper,
                            LedgerEntryMapper ledgerEntryMapper,
                            InvLedgerService ledgerService) {
        this.taskMapper = taskMapper;
        this.lineMapper = lineMapper;
        this.scanMapper = scanMapper;
        this.diffOrderMapper = diffOrderMapper;
        this.supplementMapper = supplementMapper;
        this.hallService = hallService;
        this.hallRelicMapper = hallRelicMapper;
        this.relicMapper = relicMapper;
        this.loanMapper = loanMapper;
        this.ledgerEntryMapper = ledgerEntryMapper;
        this.ledgerService = ledgerService;
    }

    // ================================================================
    // 任务生成（按展厅；一展厅同时一个在途任务，重复生成幂等）
    // ================================================================

    @Transactional
    public InvTask generate(InvTaskGenerateRequest req) {
        Hall hall = hallService.requireHall(req.getHallId());
        if (!HALL_ACTIVE.equals(hall.getStatus())) {
            throw new IllegalArgumentException("展厅已关闭，不能生成清点任务: " + hall.getHallNo());
        }
        InvTask open = openTaskOfHall(hall.getId());
        if (open != null) {
            // 幂等：同展厅重复生成命中同一在途任务，不产生第二条
            return open;
        }
        Integer maxRound = taskMapper.selectList(new LambdaQueryWrapper<InvTask>()
                        .eq(InvTask::getHallId, hall.getId())
                        .orderByDesc(InvTask::getRoundNo)
                        .last("LIMIT 1"))
                .stream().findFirst().map(InvTask::getRoundNo).orElse(0);

        InvTask task = new InvTask();
        task.setTaskNo("INV-" + String.format("%06d", taskMapper.nextTaskNo()));
        task.setHallId(hall.getId());
        task.setRoundNo(maxRound + 1);
        task.setBaselineVersion(1);
        task.setBaselineEventId(maxLedgerEventId());
        task.setBaselineAt(LocalDateTime.now());
        task.setOpenKey(hall.getId());
        task.setStatus(INV_TASK_OPEN);
        List<InvTaskLine> lines = snapshotLines(task);
        task.setExpectedCount(lines.size());
        try {
            taskMapper.insert(task);
        } catch (DuplicateKeyException dup) {
            // 并发撞 open_key：另一请求已生成，本请求回滚后由调用方重查
            throw new IllegalArgumentException("展厅 " + hall.getHallNo() + " 已存在清点中的任务，请勿重复生成");
        }
        for (InvTaskLine line : lines) {
            line.setTaskId(task.getId());
            lineMapper.insert(line);
        }
        ledgerService.append(hall.getId(), task.getId(), null,
                ACTION_INV_TASK_GENERATE, task.getTaskNo(),
                "按展厅生成清点任务 " + task.getTaskNo() + "（第 " + task.getRoundNo()
                        + " 轮，基线版本 v1，应清点 " + lines.size() + " 件），登记人："
                        + (req.getOperatorName() == null ? "未登记" : req.getOperatorName()),
                task.getBaselineAt());
        return task;
    }

    // ================================================================
    // 扫描（箱号 + 展签；版本冲突拒绝；只追加）
    // ================================================================

    @Transactional
    public InvScan scan(Long taskId, InvScanRequest req) {
        InvTask task = requireOpenTask(taskId);
        requireBaselineConsistent(task);
        int version = task.getBaselineVersion();
        if (diffOrderOfVersion(task.getId(), version) != null) {
            throw new IllegalArgumentException("差异单已开立，基线版本 v" + version
                    + " 扫描已封存；如需继续清点请重基线生成新版本");
        }
        Long dup = scanMapper.selectCount(new LambdaQueryWrapper<InvScan>()
                .eq(InvScan::getTaskId, task.getId())
                .eq(InvScan::getBaselineVersion, version)
                .eq(InvScan::getDisplayNo, req.getDisplayNo().trim()));
        if (dup != null && dup > 0) {
            throw new IllegalArgumentException("展签 " + req.getDisplayNo()
                    + " 在基线版本 v" + version + " 已扫描，请勿重复扫描");
        }
        List<InvTaskLine> lines = currentLines(task);
        LocalDateTime scannedAt = req.getScannedAt() == null ? LocalDateTime.now() : req.getScannedAt();
        InvScan scan = buildScan(task.getId(), version, lines,
                trim(req.getBoxNo()), req.getDisplayNo().trim(), trim(req.getPointCode()),
                req.getScannedBy().trim(), scannedAt, null);
        scanMapper.insert(scan);
        ledgerService.append(task.getHallId(), task.getId(), null,
                ACTION_INV_SCAN, task.getTaskNo(),
                "扫描展签 " + scan.getDisplayNo()
                        + (scan.getBoxNo() == null ? "" : " / 箱号 " + scan.getBoxNo())
                        + " → " + scan.getMatchResult()
                        + (scan.getMatchDetail() == null ? "" : "（" + scan.getMatchDetail() + "）")
                        + "，扫描人：" + scan.getScannedBy(),
                scannedAt);
        return scan;
    }

    // ================================================================
    // 重基线：旧数据回放后提交新版本（借还/展陈变更后的唯一推进方式）
    // ================================================================

    @Transactional
    public InvTask rebaseline(Long taskId, InvRebaselineRequest req) {
        InvTask task = requireOpenTask(taskId);
        int oldVersion = task.getBaselineVersion();
        int newVersion = oldVersion + 1;

        List<InvTaskLine> lines = snapshotLines(task);
        task.setBaselineVersion(newVersion);
        task.setBaselineEventId(maxLedgerEventId());
        task.setBaselineAt(LocalDateTime.now());
        task.setExpectedCount(lines.size());
        taskMapper.updateById(task);
        for (InvTaskLine line : lines) {
            line.setTaskId(task.getId());
            line.setBaselineVersion(newVersion);
            lineMapper.insert(line);
        }

        // 旧版本扫描逐条回放到新版本：判定按新基线重算，来源行可溯，旧行不改写
        List<InvScan> oldScans = scansOfVersion(task.getId(), oldVersion);
        int replayed = 0;
        for (InvScan old : oldScans) {
            InvScan replay = buildScan(task.getId(), newVersion, lines,
                    old.getBoxNo(), old.getDisplayNo(), old.getPointCode(),
                    old.getScannedBy(), old.getScannedAt(), old.getId());
            scanMapper.insert(replay);
            replayed++;
        }
        ledgerService.append(task.getHallId(), task.getId(), null,
                ACTION_INV_REBASELINE, task.getTaskNo(),
                "重基线生成版本 v" + newVersion + "（应清点 " + lines.size() + " 件），旧版本 v"
                        + oldVersion + " 扫描回放 " + replayed + " 条，操作人：" + req.getOperatorName(),
                task.getBaselineAt());
        return task;
    }

    // ================================================================
    // 差异清单（系统列出缺失、错位和状态异常；按当前版本即时计算）
    // ================================================================

    public Map<String, Object> diffReport(Long taskId) {
        InvTask task = requireTask(taskId);
        List<DiffEntry> diffs = computeDifferences(task);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("task", task);
        report.put("baselineVersion", task.getBaselineVersion());
        report.put("missing", diffs.stream().filter(d -> INV_DIFF_MISSING.equals(d.type)).collect(Collectors.toList()));
        report.put("misplaced", diffs.stream().filter(d -> INV_DIFF_MISPLACED.equals(d.type)).collect(Collectors.toList()));
        report.put("abnormal", diffs.stream().filter(d -> INV_DIFF_ABNORMAL.equals(d.type)).collect(Collectors.toList()));
        return report;
    }

    /** 当前版本差异计算：缺失（基线行无扫描）、错位、状态异常（含未在基线） */
    public List<DiffEntry> computeDifferences(InvTask task) {
        int version = task.getBaselineVersion();
        List<InvTaskLine> lines = currentLines(task);
        List<InvScan> scans = scansOfVersion(task.getId(), version);
        Map<Long, InvTaskLine> lineById = lines.stream()
                .collect(Collectors.toMap(InvTaskLine::getId, l -> l));
        Set<Long> coveredLineIds = scans.stream()
                .map(InvScan::getMatchedLineId).filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<DiffEntry> diffs = new ArrayList<>();
        for (InvTaskLine line : lines) {
            if (!coveredLineIds.contains(line.getId())) {
                diffs.add(new DiffEntry(INV_DIFF_MISSING, line.getRelicId(), line.getRelicNo(),
                        line.getDisplayNo(), line.getBoxNo(), null, line.getPointCode(),
                        "基线在册但未扫描到（缺失）"));
            }
        }
        for (InvScan scan : scans) {
            if (INV_SCAN_MISPLACED.equals(scan.getMatchResult())) {
                InvTaskLine line = scan.getMatchedLineId() == null ? null : lineById.get(scan.getMatchedLineId());
                diffs.add(new DiffEntry(INV_DIFF_MISPLACED,
                        line == null ? null : line.getRelicId(),
                        line == null ? null : line.getRelicNo(),
                        scan.getDisplayNo(),
                        line == null ? null : line.getBoxNo(),
                        scan.getBoxNo(),
                        scan.getPointCode() == null ? (line == null ? null : line.getPointCode()) : scan.getPointCode(),
                        scan.getMatchDetail()));
            } else if (INV_SCAN_ABNORMAL.equals(scan.getMatchResult())
                    || INV_SCAN_UNEXPECTED.equals(scan.getMatchResult())) {
                InvTaskLine line = scan.getMatchedLineId() == null ? null : lineById.get(scan.getMatchedLineId());
                diffs.add(new DiffEntry(INV_DIFF_ABNORMAL,
                        line == null ? null : line.getRelicId(),
                        line == null ? null : line.getRelicNo(),
                        scan.getDisplayNo(),
                        line == null ? null : line.getBoxNo(),
                        scan.getBoxNo(),
                        scan.getPointCode(),
                        scan.getMatchDetail()));
            }
        }
        return diffs;
    }

    /** 差异条目（差异单明细与差异清单共用口径） */
    public static class DiffEntry {
        public final String type;
        public final Long relicId;
        public final String relicNo;
        public final String displayNo;
        public final String boxNoExpected;
        public final String boxNoActual;
        public final String pointCode;
        public final String detail;

        public DiffEntry(String type, Long relicId, String relicNo, String displayNo,
                         String boxNoExpected, String boxNoActual, String pointCode, String detail) {
            this.type = type;
            this.relicId = relicId;
            this.relicNo = relicNo;
            this.displayNo = displayNo;
            this.boxNoExpected = boxNoExpected;
            this.boxNoActual = boxNoActual;
            this.pointCode = pointCode;
            this.detail = detail;
        }

        public String getType() { return type; }
        public Long getRelicId() { return relicId; }
        public String getRelicNo() { return relicNo; }
        public String getDisplayNo() { return displayNo; }
        public String getBoxNoExpected() { return boxNoExpected; }
        public String getBoxNoActual() { return boxNoActual; }
        public String getPointCode() { return pointCode; }
        public String getDetail() { return detail; }
    }

    // ================================================================
    // 任务关闭（差异全部复核完成后方可关闭；关闭后只能新增补充记录）
    // ================================================================

    @Transactional
    public InvTask close(Long taskId, InvTaskCloseRequest req) {
        InvTask task = requireOpenTask(taskId);
        requireBaselineConsistent(task);

        List<InvDiffOrder> orders = diffOrderMapper.selectList(new LambdaQueryWrapper<InvDiffOrder>()
                .eq(InvDiffOrder::getTaskId, task.getId()));
        for (InvDiffOrder order : orders) {
            if (!INV_DIFF_REVIEWED.equals(order.getStatus())) {
                throw new IllegalArgumentException("差异单 " + order.getDiffNo()
                        + " 尚未复核完成，清点任务不能关闭（复核完成前不能关闭展览）");
            }
        }
        List<DiffEntry> diffs = computeDifferences(task);
        if (!diffs.isEmpty() && diffOrderOfVersion(task.getId(), task.getBaselineVersion()) == null) {
            throw new IllegalArgumentException("当前基线版本 v" + task.getBaselineVersion()
                    + " 存在 " + diffs.size() + " 条差异未开立差异单，请先开立差异单并复核（差异必须能解释）");
        }

        int matched = 0;
        for (InvScan scan : scansOfVersion(task.getId(), task.getBaselineVersion())) {
            if (INV_SCAN_MATCHED.equals(scan.getMatchResult())) {
                matched++;
            }
        }
        LocalDateTime now = LocalDateTime.now();
        task.setMatchedCount(matched);
        task.setMissingCount((int) diffs.stream().filter(d -> INV_DIFF_MISSING.equals(d.type)).count());
        task.setMisplacedCount((int) diffs.stream().filter(d -> INV_DIFF_MISPLACED.equals(d.type)).count());
        task.setAbnormalCount((int) diffs.stream().filter(d -> INV_DIFF_ABNORMAL.equals(d.type)).count());
        task.setStatus(INV_TASK_CLOSED);
        task.setOpenKey(null);
        task.setClosedBy(req.getClosedBy().trim());
        task.setClosedAt(now);
        taskMapper.updateById(task);
        ledgerService.append(task.getHallId(), task.getId(), null,
                ACTION_INV_TASK_CLOSE, task.getTaskNo(),
                "清点任务关闭（基线版本 v" + task.getBaselineVersion() + "）：相符 " + matched
                        + "，缺失 " + task.getMissingCount() + "，错位 " + task.getMisplacedCount()
                        + "，状态异常 " + task.getAbnormalCount() + "，关闭人：" + req.getClosedBy(),
                now);
        return task;
    }

    // ================================================================
    // 补充记录（已关闭任务唯一允许的新增；原结果不重写）
    // ================================================================

    @Transactional
    public InvSupplement supplement(Long taskId, InvSupplementRequest req) {
        InvTask task = requireTask(taskId);
        InvSupplement supplement = new InvSupplement();
        supplement.setTaskId(task.getId());
        supplement.setContent(req.getContent().trim());
        supplement.setOperatorName(req.getOperatorName().trim());
        supplementMapper.insert(supplement);
        ledgerService.append(task.getHallId(), task.getId(), null,
                ACTION_INV_SUPPLEMENT, task.getTaskNo(),
                "补充记录（任务" + (INV_TASK_CLOSED.equals(task.getStatus()) ? "已关闭" : "清点中")
                        + "，原结果不重写）：" + req.getContent().trim() + "，记录人：" + req.getOperatorName(),
                LocalDateTime.now());
        return supplement;
    }

    // ================================================================
    // 查询
    // ================================================================

    public InvTask requireTask(Long taskId) {
        InvTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("清点任务不存在: id=" + taskId);
        }
        return task;
    }

    public List<InvTask> listTasks(Long hallId, String status) {
        return taskMapper.selectList(new LambdaQueryWrapper<InvTask>()
                .eq(hallId != null, InvTask::getHallId, hallId)
                .eq(status != null && !status.isEmpty(), InvTask::getStatus, status)
                .orderByAsc(InvTask::getId));
    }

    public Map<String, Object> detail(Long taskId) {
        InvTask task = requireTask(taskId);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("task", task);
        detail.put("lines", currentLines(task));
        detail.put("scans", scansOfVersion(task.getId(), task.getBaselineVersion()));
        detail.put("diffOrders", diffOrderMapper.selectList(new LambdaQueryWrapper<InvDiffOrder>()
                .eq(InvDiffOrder::getTaskId, task.getId())
                .orderByAsc(InvDiffOrder::getId)));
        detail.put("supplements", supplementMapper.selectList(new LambdaQueryWrapper<InvSupplement>()
                .eq(InvSupplement::getTaskId, task.getId())
                .orderByAsc(InvSupplement::getId)));
        return detail;
    }

    public List<InvTaskLine> currentLines(InvTask task) {
        return lineMapper.selectList(new LambdaQueryWrapper<InvTaskLine>()
                .eq(InvTaskLine::getTaskId, task.getId())
                .eq(InvTaskLine::getBaselineVersion, task.getBaselineVersion())
                .orderByAsc(InvTaskLine::getId));
    }

    public List<InvScan> scansOfVersion(Long taskId, int version) {
        return scanMapper.selectList(new LambdaQueryWrapper<InvScan>()
                .eq(InvScan::getTaskId, taskId)
                .eq(InvScan::getBaselineVersion, version)
                .orderByAsc(InvScan::getId));
    }

    public InvTask openTaskOfHall(Long hallId) {
        return taskMapper.selectOne(new LambdaQueryWrapper<InvTask>()
                .eq(InvTask::getHallId, hallId)
                .eq(InvTask::getStatus, INV_TASK_OPEN));
    }

    // ================================================================
    // 版本冲突检测：清点期间发生借还变更（在借单与基线快照不一致）
    // 或展陈变更（在展集合变化）时，扫描/差异单/关闭一律提示版本冲突
    // ================================================================

    public void requireBaselineConsistent(InvTask task) {
        List<InvTaskLine> lines = currentLines(task);
        List<String> loanChanges = new ArrayList<>();
        for (InvTaskLine line : lines) {
            RelicLoan openLoan = openLoanOf(line.getRelicId());
            Long currentLoanId = openLoan == null ? null : openLoan.getId();
            if (!Objects.equals(currentLoanId, line.getBaselineLoanId())) {
                loanChanges.add(line.getRelicNo() + "（基线在借单 "
                        + (line.getBaselineLoanId() == null ? "无" : line.getBaselineLoanId())
                        + " → 当前 " + (currentLoanId == null ? "无" : currentLoanId) + "）");
            }
        }
        if (!loanChanges.isEmpty()) {
            throw new IllegalArgumentException("清点期间发生借还变更：" + String.join("、", loanChanges)
                    + "，基线版本 v" + task.getBaselineVersion() + " 已失效（版本冲突），请重基线生成新版本后再继续");
        }
        Set<Long> baselineRelics = lines.stream().map(InvTaskLine::getRelicId).collect(Collectors.toSet());
        Set<Long> currentRelics = hallRelicMapper.selectList(new LambdaQueryWrapper<HallRelic>()
                        .eq(HallRelic::getHallId, task.getHallId())
                        .eq(HallRelic::getStatus, DISPLAY_ON))
                .stream().map(HallRelic::getRelicId).collect(Collectors.toSet());
        if (!baselineRelics.equals(currentRelics)) {
            Set<String> changed = new HashSet<>();
            for (InvTaskLine line : lines) {
                if (!currentRelics.contains(line.getRelicId())) {
                    changed.add(line.getRelicNo() + " 已撤展");
                }
            }
            currentRelics.stream().filter(id -> !baselineRelics.contains(id))
                    .forEach(id -> changed.add("relicId=" + id + " 新上展"));
            throw new IllegalArgumentException("清点期间发生展陈变更：" + String.join("、", changed)
                    + "，基线版本 v" + task.getBaselineVersion() + " 已失效（版本冲突），请重基线生成新版本后再继续");
        }
    }

    // ================================================================
    // 内部：基线快照 / 扫描判定 / 在借查询
    // ================================================================

    private InvTask requireOpenTask(Long taskId) {
        InvTask task = requireTask(taskId);
        if (!INV_TASK_OPEN.equals(task.getStatus())) {
            throw new IllegalArgumentException("清点任务 " + task.getTaskNo()
                    + " 已关闭，只能新增补充记录，不能重写原结果");
        }
        return task;
    }

    /** 基线快照：展厅在展文物的展签/箱号/点位与在借单 id（只追加，不改旧版本） */
    private List<InvTaskLine> snapshotLines(InvTask task) {
        List<InvTaskLine> lines = new ArrayList<>();
        for (HallRelic hr : hallService.listOnDisplay(task.getHallId())) {
            Relic relic = relicMapper.selectById(hr.getRelicId());
            InvTaskLine line = new InvTaskLine();
            line.setTaskId(task.getId());
            line.setBaselineVersion(task.getBaselineVersion());
            line.setRelicId(hr.getRelicId());
            line.setRelicNo(relic == null ? null : relic.getRelicNo());
            line.setMaterial(hr.getMaterial());
            line.setPointCode(hr.getPointCode());
            line.setDisplayNo(hr.getDisplayNo());
            line.setBoxNo(hr.getBoxNo());
            RelicLoan openLoan = openLoanOf(hr.getRelicId());
            line.setBaselineLoanId(openLoan == null ? null : openLoan.getId());
            lines.add(line);
        }
        return lines;
    }

    /** 扫描判定：展签定位基线行，箱号/点位不符为错位，撤销或在借却在场为状态异常 */
    private InvScan buildScan(Long taskId, int version, List<InvTaskLine> lines,
                              String boxNo, String displayNo, String pointCode,
                              String scannedBy, LocalDateTime scannedAt, Long replayedFromId) {
        InvScan scan = new InvScan();
        scan.setTaskId(taskId);
        scan.setBaselineVersion(version);
        scan.setBoxNo(boxNo);
        scan.setDisplayNo(displayNo);
        scan.setPointCode(pointCode);
        scan.setScannedBy(scannedBy);
        scan.setScannedAt(scannedAt);
        scan.setReplayedFromId(replayedFromId);

        InvTaskLine line = lines.stream()
                .filter(l -> Objects.equals(l.getDisplayNo(), displayNo))
                .findFirst().orElse(null);
        if (line == null) {
            InvTaskLine lineByBox = boxNo == null ? null : lines.stream()
                    .filter(l -> Objects.equals(l.getBoxNo(), boxNo))
                    .findFirst().orElse(null);
            if (lineByBox != null) {
                scan.setMatchResult(INV_SCAN_MISPLACED);
                scan.setMatchDetail("箱号 " + boxNo + " 对应基线展签 " + lineByBox.getDisplayNo()
                        + "，实际扫描展签 " + displayNo + "（错位）");
            } else {
                scan.setMatchResult(INV_SCAN_UNEXPECTED);
                scan.setMatchDetail("展签 " + displayNo + " 未在本任务基线中（状态异常）");
            }
            return scan;
        }
        scan.setMatchedLineId(line.getId());
        Relic relic = relicMapper.selectById(line.getRelicId());
        if (relic == null || REVOKED.equals(relic.getStatus())) {
            scan.setMatchResult(INV_SCAN_ABNORMAL);
            scan.setMatchDetail("文物 " + line.getRelicNo() + " 已撤销却仍陈展（状态异常）");
            return scan;
        }
        RelicLoan openLoan = openLoanOf(line.getRelicId());
        if (openLoan != null) {
            scan.setMatchResult(INV_SCAN_ABNORMAL);
            scan.setMatchDetail("文物 " + line.getRelicNo() + " 在借未还（借出单 "
                    + openLoan.getLoanNo() + "）却在场（状态异常）");
            return scan;
        }
        boolean boxMismatch = !Objects.equals(line.getBoxNo(), boxNo);
        boolean pointMismatch = pointCode != null && !Objects.equals(line.getPointCode(), pointCode);
        if (boxMismatch || pointMismatch) {
            scan.setMatchResult(INV_SCAN_MISPLACED);
            StringBuilder detail = new StringBuilder();
            if (boxMismatch) {
                detail.append("期望箱号 ").append(line.getBoxNo() == null ? "无" : line.getBoxNo())
                        .append("，实际箱号 ").append(boxNo == null ? "无" : boxNo);
            }
            if (pointMismatch) {
                if (detail.length() > 0) {
                    detail.append("；");
                }
                detail.append("期望点位 ").append(line.getPointCode()).append("，实际点位 ").append(pointCode);
            }
            scan.setMatchDetail(detail.toString());
            return scan;
        }
        scan.setMatchResult(INV_SCAN_MATCHED);
        return scan;
    }

    private RelicLoan openLoanOf(Long relicId) {
        return loanMapper.selectOne(new LambdaQueryWrapper<RelicLoan>()
                .eq(RelicLoan::getRelicId, relicId)
                .eq(RelicLoan::getStatus, LOAN_LENT)
                .orderByDesc(RelicLoan::getId)
                .last("LIMIT 1"));
    }

    private InvDiffOrder diffOrderOfVersion(Long taskId, int version) {
        return diffOrderMapper.selectOne(new LambdaQueryWrapper<InvDiffOrder>()
                .eq(InvDiffOrder::getTaskId, taskId)
                .eq(InvDiffOrder::getBaselineVersion, version));
    }

    private Long maxLedgerEventId() {
        LedgerEntry last = ledgerEntryMapper.selectOne(new LambdaQueryWrapper<LedgerEntry>()
                .orderByDesc(LedgerEntry::getId)
                .last("LIMIT 1"));
        return last == null ? 0L : last.getId();
    }

    private String trim(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
