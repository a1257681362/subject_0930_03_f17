package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.config.MuseumEnvProperties;
import com.evops.dto.EventHandleRequest;
import com.evops.dto.EventReviewRequest;
import com.evops.dto.ReadingSubmitRequest;
import com.evops.entity.EnvEvent;
import com.evops.entity.EnvEventPhoto;
import com.evops.entity.EnvReading;
import com.evops.entity.EnvReadingDetail;
import com.evops.entity.EnvRuleItem;
import com.evops.entity.EnvRuleVersion;
import com.evops.entity.Hall;
import com.evops.entity.HallRelic;
import com.evops.entity.InspectionTask;
import com.evops.entity.PhotoFile;
import com.evops.mapper.EnvEventMapper;
import com.evops.mapper.EnvEventPhotoMapper;
import com.evops.mapper.EnvReadingDetailMapper;
import com.evops.mapper.EnvReadingMapper;
import com.evops.mapper.InspectionTaskMapper;
import com.evops.mapper.PhotoFileMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static com.evops.constant.MuseumConstants.*;

/**
 * 展厅环境巡检核心服务：
 *
 * <ol>
 *   <li><b>按展厅生成任务</b>：节拍取展厅在展文物各材质采样频率最小值，
 *       (hallId, roundNo) 幂等；任务固化生成时生效规则版本快照。</li>
 *   <li><b>读数判定</b>：按任务固化的规则版本，对每点位<b>逐材质</b>做温湿度/照度
 *       越限判定（不同材质引用不同阈值），阈值与版本随读数及按材质明细固化；
 *       规则新版本不改写已完成巡检。</li>
 *   <li><b>连续超限合并</b>：同展厅同点位同指标同材质的连续越限并入同一在途事件；
 *       该材质口径指标恢复后置 RESOLVED，再次越限另开新事件。</li>
 *   <li><b>处置与复核</b>：事件须登记处置人并关联现场照片；
 *       保管员复核通过才 CLOSED，复核不通过退回 HANDLING，系统停留待处理。</li>
 * </ol>
 */
@Service
public class InspectionService {

    private static final Logger log = LoggerFactory.getLogger(InspectionService.class);

    private final InspectionTaskMapper taskMapper;
    private final EnvReadingMapper readingMapper;
    private final EnvReadingDetailMapper readingDetailMapper;
    private final EnvEventMapper eventMapper;
    private final EnvEventPhotoMapper eventPhotoMapper;
    private final PhotoFileMapper photoFileMapper;
    private final HallService hallService;
    private final EnvRuleService ruleService;
    private final EnvLedgerService ledgerService;
    private final ImageStorage imageStorage;
    private final MuseumEnvProperties properties;

    private final ReentrantLock generateLock = new ReentrantLock();
    private final AtomicBoolean startedUp = new AtomicBoolean(false);

    public InspectionService(InspectionTaskMapper taskMapper,
                             EnvReadingMapper readingMapper,
                             EnvReadingDetailMapper readingDetailMapper,
                             EnvEventMapper eventMapper,
                             EnvEventPhotoMapper eventPhotoMapper,
                             PhotoFileMapper photoFileMapper,
                             HallService hallService,
                             EnvRuleService ruleService,
                             EnvLedgerService ledgerService,
                             ImageStorage imageStorage,
                             MuseumEnvProperties properties) {
        this.taskMapper = taskMapper;
        this.readingMapper = readingMapper;
        this.readingDetailMapper = readingDetailMapper;
        this.eventMapper = eventMapper;
        this.eventPhotoMapper = eventPhotoMapper;
        this.photoFileMapper = photoFileMapper;
        this.hallService = hallService;
        this.ruleService = ruleService;
        this.ledgerService = ledgerService;
        this.imageStorage = imageStorage;
        this.properties = properties;
    }

    // ================================================================
    // 任务生成（定时 + 手动，幂等）
    // ================================================================

    /** 启动就绪补生成：重启后把到期未生成的轮次补上（巡检计划不丢轮次） */
    @EventListener(ApplicationReadyEvent.class)
    public void generateOnStartup() {
        if (properties.isGenerateOnStartup() && startedUp.compareAndSet(false, true)) {
            try {
                log.info("展厅环境巡检：启动补生成到期任务");
                generateDue(false);
            } catch (Exception e) {
                log.warn("启动补生成巡检任务失败（将由周期扫描重试）: {}", e.getMessage());
            }
        }
    }

    /** 周期生成（fixedDelay：上一轮结束后再计时，不重叠） */
    @Scheduled(fixedDelayString = "${museum.env.generate-interval-ms:60000}")
    public void generateOnSchedule() {
        generateDue(false);
    }

    /**
     * 生成到期巡检任务。
     *
     * @param force true 时忽略节拍到点判断，立即为每个开放展厅补一轮（手动触发/测试用）；
     *              同一轮仍受 (hallId, roundNo) 唯一约束保护
     * @return 本轮实际生成的任务
     */
    public List<InspectionTask> generateDue(boolean force) {
        if (!generateLock.tryLock()) {
            log.debug("巡检任务生成上一轮尚未结束，本轮跳过");
            return new ArrayList<>();
        }
        try {
            EnvRuleVersion active = ruleService.activeVersionEntity();
            if (active == null) {
                // 规则未发布：不生成任务，系统停留在"待规则生效"状态，不猜测阈值
                log.debug("尚未发布生效的环境巡检规则，跳过任务生成");
                return new ArrayList<>();
            }
            List<EnvRuleItem> items = ruleService.listItems(active.getId());
            List<InspectionTask> generated = new ArrayList<>();
            LocalDateTime now = LocalDateTime.now();
            for (Hall hall : hallService.listHalls(HALL_ACTIVE)) {
                try {
                    InspectionTask task = generateForHall(hall, active, items, now, force);
                    if (task != null) {
                        generated.add(task);
                    }
                } catch (DuplicateKeyException dup) {
                    // 定时/手动并发撞 (hall_id, round_no)：该轮已由另一方生成，跳过
                    log.debug("展厅 {} 巡检轮次已存在，跳过", hall.getHallNo());
                }
            }
            return generated;
        } finally {
            generateLock.unlock();
        }
    }

    private InspectionTask generateForHall(Hall hall, EnvRuleVersion version,
                                           List<EnvRuleItem> items,
                                           LocalDateTime now, boolean force) {
        List<HallRelic> onDisplay = hallService.listOnDisplay(hall.getId());
        if (onDisplay.isEmpty()) {
            return null;
        }
        Set<String> materials = onDisplay.stream()
                .map(HallRelic::getMaterial)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> points = onDisplay.stream()
                .map(HallRelic::getPointCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // 节拍：命中规则的各材质采样频率最小值（最严格者决定巡检密度）；
        // 全部材质都没有规则条目时用配置兜底频率
        List<EnvRuleItem> hit = items.stream()
                .filter(i -> materials.contains(i.getMaterial()))
                .collect(Collectors.toList());
        int interval = hit.isEmpty() ? properties.getDefaultIntervalMin()
                : hit.stream().mapToInt(EnvRuleItem::getSampleIntervalMin).min().getAsInt();

        InspectionTask last = taskMapper.selectOne(new LambdaQueryWrapper<InspectionTask>()
                .eq(InspectionTask::getHallId, hall.getId())
                .orderByDesc(InspectionTask::getRoundNo)
                .last("LIMIT 1"));
        if (last != null && TASK_PENDING.equals(last.getStatus())) {
            // 每个展厅同时只存在一个待巡检轮次：手动连点/定时与手动重叠均跳过
            return null;
        }
        int nextRound = last == null ? 1 : last.getRoundNo() + 1;
        LocalDateTime plannedAt;
        if (last == null || force) {
            plannedAt = now;
        } else {
            LocalDateTime dueAt = last.getDueAt();
            if (now.isBefore(dueAt)) {
                return null; // 节拍未到
            }
            plannedAt = dueAt; // 补生成沿用计划时点，巡检计划时间序列不漂移
        }

        InspectionTask task = new InspectionTask();
        task.setTaskNo("IT-" + hall.getHallNo() + "-R" + nextRound);
        task.setHallId(hall.getId());
        task.setRoundNo(nextRound);
        // 规则版本快照：新版本发布不改写已生成/已完成任务
        task.setRuleVersionId(version.getId());
        task.setRuleVersionNo(version.getVersionNo());
        task.setIntervalMin(interval);
        task.setPointCount(points.size());
        task.setPlannedAt(plannedAt);
        task.setDueAt(plannedAt.plusMinutes(interval));
        task.setStatus(TASK_PENDING);
        taskMapper.insert(task);

        ledgerService.append(hall.getId(), task.getId(), null,
                version.getId(), version.getVersionNo(),
                ACTION_TASK_GENERATE, task.getTaskNo(),
                "按展厅生成第 " + nextRound + " 轮巡检任务，节拍 " + interval
                        + " 分钟，点位 " + points.size() + " 个，采用规则版本 "
                        + version.getVersionNo(), plannedAt);
        return task;
    }

    // ================================================================
    // 读数提交与事件归并
    // ================================================================

    /**
     * 提交一轮巡检读数：按任务固化的规则版本逐材质判定，结果随读数固化；
     * 连续超限合并为同一事件，材质口径指标恢复后事件转待保管员复核。
     * 任务一经完成不可再次提交（已完成巡检不改写）。
     */
    @Transactional
    public InspectionTask submitReadings(Long taskId, ReadingSubmitRequest req) {
        InspectionTask task = requireTask(taskId);
        if (!TASK_PENDING.equals(task.getStatus())) {
            throw new IllegalArgumentException("巡检任务" + task.getTaskNo() + "已"
                    + (TASK_DONE.equals(task.getStatus()) ? "完成" : "取消")
                    + "，读数不可改写");
        }
        Hall hall = hallService.requireHall(task.getHallId());
        EnvRuleVersion version = ruleService.requireVersion(task.getRuleVersionId());
        List<EnvRuleItem> items = ruleService.listItems(version.getId());
        Map<String, EnvRuleItem> itemByMaterial = items.stream()
                .collect(Collectors.toMap(EnvRuleItem::getMaterial, i -> i, (a, b) -> a));

        // 点位 -> 在展文物材质集合
        Map<String, Set<String>> pointMaterials = hallService.listOnDisplay(hall.getId())
                .stream()
                .collect(Collectors.groupingBy(HallRelic::getPointCode,
                        Collectors.mapping(HallRelic::getMaterial, Collectors.toSet())));

        Set<String> taskPoints = new TreeSet<>(pointMaterials.keySet());
        LocalDateTime readAt = req.getReadAt() == null ? LocalDateTime.now() : req.getReadAt();
        List<String> abnormalSummaries = new ArrayList<>();

        // 一轮任务必须覆盖展厅全部在展点位，且不允许同点位重复提交
        Set<String> submittedPoints = new LinkedHashSet<>();
        for (ReadingSubmitRequest.ReadingPoint p : req.getReadings()) {
            if (!submittedPoints.add(p.getPointCode())) {
                throw new IllegalArgumentException("同一点位读数不能重复提交: " + p.getPointCode());
            }
            if (!taskPoints.contains(p.getPointCode())) {
                throw new IllegalArgumentException("点位不属于展厅当前在展点位: " + p.getPointCode());
            }
        }
        if (!submittedPoints.equals(taskPoints)) {
            Set<String> missing = new TreeSet<>(taskPoints);
            missing.removeAll(submittedPoints);
            throw new IllegalArgumentException("巡检读数未覆盖全部在展点位，缺少: " + missing);
        }

        for (ReadingSubmitRequest.ReadingPoint p : req.getReadings()) {
            Set<String> materials = pointMaterials.getOrDefault(p.getPointCode(), new LinkedHashSet<>());

            // 只对规则中有条目的材质判定；未覆盖材质在读数上标记，不猜测阈值
            List<EnvRuleItem> pointItems = new ArrayList<>();
            List<String> uncovered = new ArrayList<>();
            for (String material : materials) {
                EnvRuleItem item = itemByMaterial.get(material);
                if (item != null) {
                    pointItems.add(item);
                } else {
                    uncovered.add(material);
                }
            }
            if (pointItems.isEmpty()) {
                throw new IllegalArgumentException("点位 " + p.getPointCode()
                        + " 的在展材质 " + materials + " 在规则版本 " + version.getVersionNo()
                        + " 中均无阈值条目，无法判定读数");
            }

            Set<String> abnormalUnion = new LinkedHashSet<>();
            List<EnvReadingDetail> details = new ArrayList<>();
            // material -> 该材质越限指标
            Map<String, List<String>> abnormalByMaterial = new LinkedHashMap<>();

            for (EnvRuleItem item : pointItems) {
                List<String> abnormalMetrics = new ArrayList<>();
                if (outside(p.getTemperature(), item.getTempMin(), item.getTempMax())) {
                    abnormalMetrics.add(METRIC_TEMP);
                }
                if (outside(p.getHumidity(), item.getHumidityMin(), item.getHumidityMax())) {
                    abnormalMetrics.add(METRIC_HUMIDITY);
                }
                if (outside(p.getIlluminance(), item.getLuxMin(), item.getLuxMax())) {
                    abnormalMetrics.add(METRIC_LUX);
                }
                abnormalUnion.addAll(abnormalMetrics);
                abnormalByMaterial.put(item.getMaterial(), abnormalMetrics);

                EnvReadingDetail detail = new EnvReadingDetail();
                detail.setMaterial(item.getMaterial());
                detail.setTempMin(item.getTempMin());
                detail.setTempMax(item.getTempMax());
                detail.setHumidityMin(item.getHumidityMin());
                detail.setHumidityMax(item.getHumidityMax());
                detail.setLuxMin(item.getLuxMin());
                detail.setLuxMax(item.getLuxMax());
                detail.setAbnormalMetrics(abnormalMetrics.isEmpty() ? null : String.join(",", abnormalMetrics));
                details.add(detail);
            }

            EnvReading reading = new EnvReading();
            reading.setTaskId(taskId);
            reading.setHallId(hall.getId());
            reading.setPointCode(p.getPointCode());
            reading.setRuleVersionId(version.getId());
            reading.setRuleVersionNo(version.getVersionNo());
            reading.setTemperature(p.getTemperature());
            reading.setHumidity(p.getHumidity());
            reading.setIlluminance(p.getIlluminance());
            reading.setMatchedMaterials(pointItems.stream()
                    .map(EnvRuleItem::getMaterial).collect(Collectors.joining("|")));
            reading.setUncoveredMaterials(uncovered.isEmpty() ? null : String.join("|", uncovered));
            reading.setAbnormal(abnormalUnion.isEmpty() ? 0 : 1);
            reading.setAbnormalMetrics(abnormalUnion.isEmpty() ? null : String.join(",", abnormalUnion));
            reading.setReadAt(readAt);
            reading.setInspector(req.getInspector());
            readingMapper.insert(reading);
            for (EnvReadingDetail d : details) {
                d.setReadingId(reading.getId());
                readingDetailMapper.insert(d);
            }

            // 逐材质逐指标归并事件（不同材质阈值不同，分别成事件）
            for (EnvRuleItem item : pointItems) {
                List<String> metrics = abnormalByMaterial.get(item.getMaterial());
                reconcileEvent(hall, task, version, reading, item,
                        METRIC_TEMP, p.getTemperature(),
                        item.getTempMin(), item.getTempMax(),
                        metrics.contains(METRIC_TEMP), readAt);
                reconcileEvent(hall, task, version, reading, item,
                        METRIC_HUMIDITY, p.getHumidity(),
                        item.getHumidityMin(), item.getHumidityMax(),
                        metrics.contains(METRIC_HUMIDITY), readAt);
                reconcileEvent(hall, task, version, reading, item,
                        METRIC_LUX, p.getIlluminance(),
                        item.getLuxMin(), item.getLuxMax(),
                        metrics.contains(METRIC_LUX), readAt);
            }

            if (!abnormalUnion.isEmpty()) {
                String materialText = abnormalByMaterial.entrySet().stream()
                        .filter(e -> !e.getValue().isEmpty())
                        .map(e -> e.getKey() + "(" + String.join("/", metricNames(e.getValue())) + ")")
                        .collect(Collectors.joining("、"));
                abnormalSummaries.add(p.getPointCode() + "[" + materialText + "]");
                ledgerService.append(hall.getId(), taskId, null,
                        version.getId(), version.getVersionNo(),
                        ACTION_READING_ABNORMAL, task.getTaskNo(),
                        "点位 " + p.getPointCode() + " 读数异常：温度 " + p.getTemperature()
                                + "℃、湿度 " + p.getHumidity() + "%、照度 " + p.getIlluminance()
                                + "lux，按材质判定越限 " + materialText
                                + "，规则版本 " + version.getVersionNo()
                                + (uncovered.isEmpty() ? "" : "；未覆盖材质 " + uncovered + " 不参与判定"),
                        readAt);
            } else {
                ledgerService.append(hall.getId(), taskId, null,
                        version.getId(), version.getVersionNo(),
                        ACTION_READING_OK, task.getTaskNo(),
                        "点位 " + p.getPointCode() + " 各材质口径读数均正常，规则版本 "
                                + version.getVersionNo()
                                + (uncovered.isEmpty() ? "" : "；未覆盖材质 " + uncovered + " 不参与判定"),
                        readAt);
            }
        }

        task.setStatus(TASK_DONE);
        task.setInspector(req.getInspector());
        task.setFinishedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        log.info("巡检任务 {} 完成，异常：{}", task.getTaskNo(),
                abnormalSummaries.isEmpty() ? "无" : String.join("、", abnormalSummaries));
        return task;
    }

    /**
     * 单材质单指标事件归并：
     * 越限且有在途事件 → 并入（连续超限合并）；越限无在途事件 → 新开；
     * 未越限但有在途事件 → 指标恢复，事件转 RESOLVED 待保管员复核。
     */
    private void reconcileEvent(Hall hall, InspectionTask task, EnvRuleVersion version,
                                EnvReading reading, EnvRuleItem item,
                                String metric, BigDecimal value,
                                BigDecimal low, BigDecimal high, boolean abnormal,
                                LocalDateTime readAt) {
        String material = item.getMaterial();
        String mergeKey = mergeKey(hall.getId(), reading.getPointCode(), metric, material);
        EnvEvent active = eventMapper.selectOne(new LambdaQueryWrapper<EnvEvent>()
                .eq(EnvEvent::getMergeKey, mergeKey)
                .last("LIMIT 1"));

        if (abnormal) {
            if (active != null) {
                mergeIntoEvent(active, reading, value, metric, material, readAt, version);
                return;
            }
            openEvent(hall, task, version, reading, item, metric, value, low, high, mergeKey, readAt);
            return;
        }
        if (active != null) {
            // 指标恢复：连续超限段结束，转待保管员复核；清空归并键，再次越限开新事件
            active.setStatus(EVENT_RESOLVED);
            active.setMergeKey(null);
            eventMapper.updateById(active);
            ledgerService.append(hall.getId(), task.getId(), active.getId(),
                    version.getId(), version.getVersionNo(),
                    ACTION_EVENT_RESOLVE, active.getEventNo(),
                    "点位 " + active.getPointCode() + " 材质【" + material + "】" + metricName(metric)
                            + "恢复正常（读数 " + value + "），事件 " + active.getEventNo()
                            + " 连续超限 " + active.getReadingCount()
                            + " 次，转保管员复核关闭", readAt);
            // 若此前有被保管员驳回的同 quadruple 事件（HANDLING 且无归并键），
            // 把归并键转挂给最早一条，使其能随下一轮读数重新并入或恢复——不留死事件
            rearmRejectedEvent(hall.getId(), reading.getPointCode(), metric, material);
        }
    }

    /**
     * 恢复归并键给最早一条被保管员驳回（HANDLING 且 merge_key 为空）的同 quadruple 事件；
     * 键已被新异常段占用时不处理，待该段恢复时再级联转挂。
     */
    private void rearmRejectedEvent(Long hallId, String pointCode, String metric, String material) {
        String mergeKey = mergeKey(hallId, pointCode, metric, material);
        Long occupied = eventMapper.selectCount(new LambdaQueryWrapper<EnvEvent>()
                .eq(EnvEvent::getMergeKey, mergeKey));
        if (occupied != null && occupied > 0) {
            return;
        }
        EnvEvent rejected = eventMapper.selectOne(new LambdaQueryWrapper<EnvEvent>()
                .eq(EnvEvent::getHallId, hallId)
                .eq(EnvEvent::getPointCode, pointCode)
                .eq(EnvEvent::getMetric, metric)
                .eq(EnvEvent::getMaterial, material)
                .eq(EnvEvent::getStatus, EVENT_HANDLING)
                .isNull(EnvEvent::getMergeKey)
                .isNotNull(EnvEvent::getReviewedAt)
                .orderByAsc(EnvEvent::getId)
                .last("LIMIT 1"));
        if (rejected != null) {
            rejected.setMergeKey(mergeKey);
            eventMapper.updateById(rejected);
        }
    }

    private void mergeIntoEvent(EnvEvent event, EnvReading reading, BigDecimal value,
                                String metric, String material, LocalDateTime readAt,
                                EnvRuleVersion currentVersion) {
        event.setLastValue(value);
        event.setPeakValue(extreme(event.getPeakValue(), value,
                event.getThresholdLow(), event.getThresholdHigh()));
        event.setReadingCount(event.getReadingCount() + 1);
        event.setLastReadingId(reading.getId());
        event.setLastAt(readAt);
        eventMapper.updateById(event);
        String versionNote = currentVersion.getId().equals(event.getRuleVersionId())
                ? "" : "（并入读数来自规则版本 " + currentVersion.getVersionNo() + "，事件阈值仍按首警版本 "
                + event.getRuleVersionNo() + "）";
        ledgerService.append(event.getHallId(), reading.getTaskId(), event.getId(),
                currentVersion.getId(), currentVersion.getVersionNo(),
                ACTION_EVENT_MERGE, event.getEventNo(),
                "点位 " + event.getPointCode() + " 材质【" + material + "】" + metricName(metric)
                        + "连续超限（读数 " + value + "）并入事件 " + event.getEventNo()
                        + "，累计 " + event.getReadingCount() + " 次" + versionNote
                        + (EVENT_HANDLING.equals(event.getStatus()) ? "，事件处置中" : ""), readAt);
    }

    private void openEvent(Hall hall, InspectionTask task, EnvRuleVersion version,
                           EnvReading reading, EnvRuleItem item, String metric, BigDecimal value,
                           BigDecimal low, BigDecimal high, String mergeKey,
                           LocalDateTime readAt) {
        String material = item.getMaterial();
        EnvEvent event = new EnvEvent();
        event.setEventNo("EE-" + String.format("%06d", eventMapper.nextEventNo()));
        event.setHallId(hall.getId());
        event.setPointCode(reading.getPointCode());
        event.setMetric(metric);
        event.setMaterial(material);
        event.setMergeKey(mergeKey);
        event.setRuleVersionId(version.getId());
        event.setRuleVersionNo(version.getVersionNo());
        event.setThresholdLow(low);
        event.setThresholdHigh(high);
        event.setFirstValue(value);
        event.setLastValue(value);
        event.setPeakValue(value);
        event.setReadingCount(1);
        event.setFirstReadingId(reading.getId());
        event.setLastReadingId(reading.getId());
        event.setFirstAt(readAt);
        event.setLastAt(readAt);
        event.setStatus(EVENT_OPEN);
        try {
            eventMapper.insert(event);
        } catch (DuplicateKeyException dup) {
            // 并发读数同 key 首警：回查并入，保证连续超限只有一个事件
            EnvEvent raced = eventMapper.selectOne(new LambdaQueryWrapper<EnvEvent>()
                    .eq(EnvEvent::getMergeKey, mergeKey).last("LIMIT 1"));
            if (raced != null) {
                mergeIntoEvent(raced, reading, value, metric, material, readAt, version);
                return;
            }
            throw dup;
        }
        ledgerService.append(hall.getId(), task.getId(), event.getId(),
                version.getId(), version.getVersionNo(),
                ACTION_EVENT_OPEN, event.getEventNo(),
                "点位 " + reading.getPointCode() + " 材质【" + material + "】" + metricName(metric)
                        + "超限：读数 " + value + "，阈值区间 [" + low + ", " + high
                        + "]，开异常事件 " + event.getEventNo()
                        + "，规则版本 " + version.getVersionNo()
                        + "，待关联处置人与现场照片", readAt);
    }

    // ================================================================
    // 事件处置 / 现场照片 / 保管员复核
    // ================================================================

    /** 处置登记：OPEN -> HANDLING；RESOLVED 状态允许（首次）补登记处置人（关闭前置条件）；
     *  保管员驳回后（HANDLING 且有复核记录）允许重新登记处置 */
    @Transactional
    public EnvEvent handle(Long eventId, EventHandleRequest req) {
        EnvEvent event = requireEvent(eventId);
        if (EVENT_CLOSED.equals(event.getStatus())) {
            throw new IllegalArgumentException("事件" + event.getEventNo() + "已关闭，处置记录封存");
        }
        boolean afterReject = EVENT_HANDLING.equals(event.getStatus())
                && event.getReviewedAt() != null;
        if (EVENT_HANDLING.equals(event.getStatus()) && !afterReject) {
            throw new IllegalArgumentException("事件" + event.getEventNo() + "已在处置中，请勿重复登记");
        }
        // 待复核且已登记处置人、但未经保管员驳回的，不允许重复登记；
        // 驳回后重新登记（reviewedAt 非空）是整改补充，允许且不改变当前状态
        if (EVENT_RESOLVED.equals(event.getStatus())
                && event.getReviewedAt() == null
                && event.getHandler() != null && !event.getHandler().trim().isEmpty()) {
            throw new IllegalArgumentException("事件" + event.getEventNo() + "已登记处置人，不能重复登记");
        }
        LocalDateTime now = LocalDateTime.now();
        event.setHandler(req.getHandler());
        event.setHandleRemark(req.getHandleRemark());
        event.setHandledAt(now);
        if (EVENT_OPEN.equals(event.getStatus())) {
            event.setStatus(EVENT_HANDLING);
        }
        // RESOLVED：保持待复核，仅补齐处置人，供保管员复核关闭；
        // 驳回后重新登记处置：保持 HANDLING，等待下一轮读数恢复后再转 RESOLVED
        eventMapper.updateById(event);
        ledgerService.append(event.getHallId(), null, event.getId(),
                event.getRuleVersionId(), event.getRuleVersionNo(),
                ACTION_EVENT_HANDLE, event.getEventNo(),
                "事件 " + event.getEventNo() + "（" + event.getPointCode() + " "
                        + event.getMaterial() + " " + metricName(event.getMetric())
                        + "）登记处置人 " + req.getHandler() + "：" + req.getHandleRemark(), now);
        return event;
    }

    /**
     * 上传事件现场照片：复用影像归档内容寻址存储（同哈希只一份物理文件）。
     * 已关闭事件封存，不再接收照片；同一事件内同照片只登记一次。
     */
    @Transactional
    public EnvEventPhoto uploadEventPhoto(Long eventId, MultipartFile file,
                                          String uploadedBy, String remark) {
        EnvEvent event = requireEvent(eventId);
        if (EVENT_CLOSED.equals(event.getStatus())) {
            throw new IllegalArgumentException("事件" + event.getEventNo() + "已关闭，不能再补现场照片");
        }
        if (uploadedBy == null || uploadedBy.trim().isEmpty()) {
            throw new IllegalArgumentException("上传人不能为空");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("现场照片不能为空");
        }
        String fileName = file.getOriginalFilename();
        ImageStorage.StoredPhoto stored;
        try {
            // 现场照片不套用文物影像 800×800 归档下限，仅要求可解码
            stored = imageStorage.store(file.getBytes(), fileName, file.getContentType(), false);
        } catch (IOException e) {
            throw new IllegalArgumentException("现场照片读取失败");
        }
        PhotoFile photoFile = obtainPhotoFile(stored, fileName, uploadedBy);

        EnvEventPhoto exists = eventPhotoMapper.selectOne(new LambdaQueryWrapper<EnvEventPhoto>()
                .eq(EnvEventPhoto::getEventId, eventId)
                .eq(EnvEventPhoto::getContentHash, stored.hash));
        if (exists != null) {
            throw new IllegalArgumentException("该现场照片已关联事件 " + event.getEventNo()
                    + "（内容一致）：" + (exists.getPhotoName() == null ? stored.hash : exists.getPhotoName()));
        }
        EnvEventPhoto photo = new EnvEventPhoto();
        photo.setEventId(eventId);
        photo.setFileId(photoFile.getId());
        photo.setContentHash(stored.hash);
        photo.setPhotoName(stored.fileName);
        photo.setUploadedBy(uploadedBy.trim());
        photo.setRemark(remark);
        eventPhotoMapper.insert(photo);
        return photo;
    }

    private PhotoFile obtainPhotoFile(ImageStorage.StoredPhoto stored, String fileName, String uploadedBy) {
        PhotoFile existing = photoFileMapper.selectOne(new LambdaQueryWrapper<PhotoFile>()
                .eq(PhotoFile::getContentHash, stored.hash));
        if (existing != null) {
            existing.setRefCount(existing.getRefCount() + 1);
            photoFileMapper.updateById(existing);
            return existing;
        }
        PhotoFile photoFile = new PhotoFile();
        photoFile.setContentHash(stored.hash);
        photoFile.setStoragePath(stored.storagePath);
        photoFile.setFileName(stored.fileName);
        photoFile.setContentType(stored.contentType);
        photoFile.setFileSize(stored.size);
        photoFile.setWidthPx(stored.width);
        photoFile.setHeightPx(stored.height);
        photoFile.setShotAt(LocalDateTime.now());
        photoFile.setPhotographer(uploadedBy.trim());
        photoFile.setRefCount(1);
        try {
            photoFileMapper.insert(photoFile);
            return photoFile;
        } catch (DuplicateKeyException dup) {
            PhotoFile raced = photoFileMapper.selectOne(new LambdaQueryWrapper<PhotoFile>()
                    .eq(PhotoFile::getContentHash, stored.hash));
            raced.setRefCount(raced.getRefCount() + 1);
            photoFileMapper.updateById(raced);
            return raced;
        }
    }

    /**
     * 保管员复核：
     * 仅 RESOLVED（指标已恢复）事件可复核；通过时必须已有处置人与至少一张现场照片，
     * 关闭后封存；不通过退回 HANDLING，系统停留待处理，须重新处置并待指标再次恢复后复核。
     */
    @Transactional
    public EnvEvent review(Long eventId, EventReviewRequest req) {
        EnvEvent event = requireEvent(eventId);
        LocalDateTime now = LocalDateTime.now();
        if (!EVENT_RESOLVED.equals(event.getStatus())) {
            throw new IllegalArgumentException("事件" + event.getEventNo()
                    + "指标尚未恢复正常或不在待复核状态（当前 " + event.getStatus()
                    + "），系统停留待处理，不能复核关闭");
        }

        event.setReviewBy(req.getReviewBy());
        event.setReviewRemark(req.getReviewRemark());
        event.setReviewedAt(now);

        if (Boolean.TRUE.equals(req.getApproved())) {
            if (event.getHandler() == null || event.getHandler().trim().isEmpty()) {
                throw new IllegalArgumentException("事件" + event.getEventNo()
                        + "尚未登记处置人，保管员不能复核关闭");
            }
            long photoCount = eventPhotoMapper.selectCount(new LambdaQueryWrapper<EnvEventPhoto>()
                    .eq(EnvEventPhoto::getEventId, eventId));
            if (photoCount <= 0) {
                throw new IllegalArgumentException("事件" + event.getEventNo()
                        + "未关联现场照片，保管员不能复核关闭");
            }
            event.setStatus(EVENT_CLOSED);
            event.setCloseRemark(req.getCloseRemark());
            event.setClosedAt(now);
            eventMapper.updateById(event);
            ledgerService.append(event.getHallId(), null, event.getId(),
                    event.getRuleVersionId(), event.getRuleVersionNo(),
                    ACTION_EVENT_CLOSE, event.getEventNo(),
                    "保管员 " + req.getReviewBy() + " 复核通过，事件 " + event.getEventNo()
                            + "（" + event.getMaterial() + " " + metricName(event.getMetric())
                            + "）关闭；处置人 " + event.getHandler() + "，现场照片 " + photoCount
                            + " 张，首警规则版本 " + event.getRuleVersionNo(), now);
        } else {
            // 复核不通过：退回处置中，停留待处理
            event.setStatus(EVENT_HANDLING);
            eventMapper.updateById(event);
            ledgerService.append(event.getHallId(), null, event.getId(),
                    event.getRuleVersionId(), event.getRuleVersionNo(),
                    ACTION_EVENT_REVIEW_REJECT, event.getEventNo(),
                    "保管员 " + req.getReviewBy() + " 复核不通过，事件 " + event.getEventNo()
                            + " 退回处置中"
                            + (req.getReviewRemark() == null ? "" : "：" + req.getReviewRemark()), now);
        }
        return event;
    }

    // ================================================================
    // 查询（报表均标明规则版本）
    // ================================================================

    public InspectionTask requireTask(Long taskId) {
        InspectionTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("巡检任务不存在: id=" + taskId);
        }
        return task;
    }

    public EnvEvent requireEvent(Long eventId) {
        EnvEvent event = eventMapper.selectById(eventId);
        if (event == null) {
            throw new IllegalArgumentException("异常事件不存在: id=" + eventId);
        }
        return event;
    }

    public List<InspectionTask> listTasks(Long hallId, String status) {
        return taskMapper.selectList(new LambdaQueryWrapper<InspectionTask>()
                .eq(hallId != null, InspectionTask::getHallId, hallId)
                .eq(status != null && !status.isEmpty(), InspectionTask::getStatus, status)
                .orderByDesc(InspectionTask::getPlannedAt)
                .orderByDesc(InspectionTask::getId));
    }

    public List<EnvReading> listReadings(Long hallId, Long taskId) {
        return readingMapper.selectList(new LambdaQueryWrapper<EnvReading>()
                .eq(hallId != null, EnvReading::getHallId, hallId)
                .eq(taskId != null, EnvReading::getTaskId, taskId)
                .orderByAsc(EnvReading::getReadAt)
                .orderByAsc(EnvReading::getId));
    }

    public List<EnvReadingDetail> listDetailsByTask(Long taskId) {
        List<Long> readingIds = listReadings(null, taskId).stream()
                .map(EnvReading::getId).collect(Collectors.toList());
        if (readingIds.isEmpty()) {
            return new ArrayList<>();
        }
        return readingDetailMapper.selectList(new LambdaQueryWrapper<EnvReadingDetail>()
                .in(EnvReadingDetail::getReadingId, readingIds)
                .orderByAsc(EnvReadingDetail::getReadingId)
                .orderByAsc(EnvReadingDetail::getMaterial));
    }

    public List<EnvEvent> listEvents(Long hallId, String status, String pointCode, String material) {
        return eventMapper.selectList(new LambdaQueryWrapper<EnvEvent>()
                .eq(hallId != null, EnvEvent::getHallId, hallId)
                .eq(status != null && !status.isEmpty(), EnvEvent::getStatus, status)
                .eq(pointCode != null && !pointCode.isEmpty(), EnvEvent::getPointCode, pointCode)
                .eq(material != null && !material.isEmpty(), EnvEvent::getMaterial, material)
                .orderByDesc(EnvEvent::getFirstAt)
                .orderByDesc(EnvEvent::getId));
    }

    public List<EnvEventPhoto> listEventPhotos(Long eventId) {
        return eventPhotoMapper.selectList(new LambdaQueryWrapper<EnvEventPhoto>()
                .eq(EnvEventPhoto::getEventId, eventId)
                .orderByAsc(EnvEventPhoto::getId));
    }

    /** 任务报表：任务头（标明规则版本）+ 读数 + 按材质判定明细（各材质阈值快照） */
    public Map<String, Object> taskReport(Long taskId) {
        InspectionTask task = requireTask(taskId);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("task", task);
        report.put("ruleVersionId", task.getRuleVersionId());
        report.put("ruleVersionNo", task.getRuleVersionNo());
        report.put("readings", listReadings(task.getHallId(), taskId));
        report.put("readingDetails", listDetailsByTask(taskId));
        return report;
    }

    /** 事件报表：事件头（标明首警规则版本）+ 现场照片 */
    public Map<String, Object> eventReport(Long eventId) {
        EnvEvent event = requireEvent(eventId);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("event", event);
        report.put("ruleVersionId", event.getRuleVersionId());
        report.put("ruleVersionNo", event.getRuleVersionNo());
        report.put("photos", listEventPhotos(eventId));
        return report;
    }

    // ================================================================
    // 辅助
    // ================================================================

    static String mergeKey(Long hallId, String pointCode, String metric, String material) {
        return hallId + "|" + pointCode + "|" + metric + "|" + material;
    }

    private static boolean outside(BigDecimal v, BigDecimal low, BigDecimal high) {
        return v.compareTo(low) < 0 || v.compareTo(high) > 0;
    }

    /** 峰值取最偏离阈值区间的值：低越限取最小，高越限取最大 */
    private static BigDecimal extreme(BigDecimal current, BigDecimal value,
                                      BigDecimal low, BigDecimal high) {
        if (value.compareTo(low) < 0) {
            return value.min(current);
        }
        if (value.compareTo(high) > 0) {
            return value.max(current);
        }
        return current;
    }

    private static String metricName(String metric) {
        switch (metric) {
            case METRIC_TEMP: return "温度";
            case METRIC_HUMIDITY: return "湿度";
            case METRIC_LUX: return "照度";
            default: return metric;
        }
    }

    private static List<String> metricNames(List<String> metrics) {
        return metrics.stream().map(InspectionService::metricName).collect(Collectors.toList());
    }
}
