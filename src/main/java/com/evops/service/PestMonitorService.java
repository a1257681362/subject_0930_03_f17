package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.dto.PestMonthFreezeRequest;
import com.evops.dto.PestRecordSubmitRequest;
import com.evops.dto.PestTaskCloseRequest;
import com.evops.entity.PestDisposeTask;
import com.evops.entity.PestMonitorDetail;
import com.evops.entity.PestMonitorPhoto;
import com.evops.entity.PestMonitorRecord;
import com.evops.entity.PestMonthFreeze;
import com.evops.entity.PestRuleItem;
import com.evops.entity.PestRuleVersion;
import com.evops.entity.PestTrapCard;
import com.evops.entity.PestTrapPoint;
import com.evops.entity.PhotoFile;
import com.evops.mapper.PestDisposeTaskMapper;
import com.evops.mapper.PestMonitorDetailMapper;
import com.evops.mapper.PestMonitorPhotoMapper;
import com.evops.mapper.PestMonitorRecordMapper;
import com.evops.mapper.PestMonthFreezeMapper;
import com.evops.mapper.PhotoFileMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.evops.constant.MuseumConstants.*;

/**
 * 虫害监测核心服务：
 *
 * <ol>
 *   <li><b>监测提交</b>：外包人员与馆内人员均可提交现场数据（虫种数量/照片）；
 *       按提交时生效规则版本判定，版本快照、阈值快照与结论随记录固化——
 *       规则调整只生成新版本，不改写监测记录旧结论。</li>
 *   <li><b>超阈值处置</b>：任一虫种数量超过阈值即生成处置任务并关联点位责任区域；
 *       外包人员不能关闭任务，仅馆内人员可关闭。</li>
 *   <li><b>按月份冻结</b>：月份冻结后该月监测记录与照片不再接受补录/更改，
 *       已冻结事实保留可查；冻结只追加不解冻。</li>
 * </ol>
 */
@Service
public class PestMonitorService {

    private final PestMonitorRecordMapper recordMapper;
    private final PestMonitorDetailMapper detailMapper;
    private final PestMonitorPhotoMapper photoMapper;
    private final PestDisposeTaskMapper taskMapper;
    private final PestMonthFreezeMapper freezeMapper;
    private final PhotoFileMapper photoFileMapper;
    private final PestPointService pointService;
    private final PestRuleService ruleService;
    private final PestLedgerService ledgerService;
    private final ImageStorage imageStorage;

    public PestMonitorService(PestMonitorRecordMapper recordMapper,
                              PestMonitorDetailMapper detailMapper,
                              PestMonitorPhotoMapper photoMapper,
                              PestDisposeTaskMapper taskMapper,
                              PestMonthFreezeMapper freezeMapper,
                              PhotoFileMapper photoFileMapper,
                              PestPointService pointService,
                              PestRuleService ruleService,
                              PestLedgerService ledgerService,
                              ImageStorage imageStorage) {
        this.recordMapper = recordMapper;
        this.detailMapper = detailMapper;
        this.photoMapper = photoMapper;
        this.taskMapper = taskMapper;
        this.freezeMapper = freezeMapper;
        this.photoFileMapper = photoFileMapper;
        this.pointService = pointService;
        this.ruleService = ruleService;
        this.ledgerService = ledgerService;
        this.imageStorage = imageStorage;
    }

    // ================================================================
    // 监测记录提交（外包/馆内均可提交现场数据）
    // ================================================================

    /**
     * 提交监测记录：按提交时生效规则版本逐虫种判定，结论随记录固化；
     * 任一虫种超阈值即生成处置任务并关联点位责任区域。
     * 月份已冻结则拒绝补录。
     */
    @Transactional
    public PestMonitorRecord submit(PestRecordSubmitRequest req) {
        if (!PEST_ROLE_INTERNAL.equals(req.getSubmitterRole())
                && !PEST_ROLE_OUTSOURCE.equals(req.getSubmitterRole())) {
            throw new IllegalArgumentException("提交人角色只支持 INTERNAL 馆内人员 / OUTSOURCE 外包人员: "
                    + req.getSubmitterRole());
        }
        PestTrapPoint point = pointService.requirePoint(req.getPointId());
        if (!PEST_POINT_ACTIVE.equals(point.getStatus())) {
            throw new IllegalArgumentException("点位" + point.getPointNo() + "已停用，不能提交监测记录");
        }
        PestTrapCard card = pointService.activeCard(point.getId());
        if (card == null) {
            throw new IllegalArgumentException("点位" + point.getPointNo() + "无在布诱捕片，请先布放诱捕片");
        }

        LocalDateTime submittedAt = req.getSubmittedAt() == null
                ? LocalDateTime.now() : req.getSubmittedAt();
        String month = req.getMonitorMonth() == null || req.getMonitorMonth().trim().isEmpty()
                ? submittedAt.format(DateTimeFormatter.ofPattern("yyyy-MM"))
                : req.getMonitorMonth().trim();
        requireMonthNotFrozen(month);

        PestRuleVersion version = ruleService.requireActiveVersion();
        Map<String, PestRuleItem> itemBySpecies = ruleService.listItems(version.getId())
                .stream()
                .collect(Collectors.toMap(PestRuleItem::getPestSpecies, i -> i, (a, b) -> a));

        // 同一记录内虫种不可重复
        Set<String> seen = new LinkedHashSet<>();
        for (PestRecordSubmitRequest.SpeciesCount d : req.getDetails()) {
            if (!seen.add(d.getPestSpecies().trim())) {
                throw new IllegalArgumentException("同一记录内虫种不可重复: " + d.getPestSpecies());
            }
        }

        // 逐虫种判定：规则有条目的按阈值判定并固化快照；无条目的不猜测阈值
        List<PestMonitorDetail> details = new ArrayList<>();
        List<String> overSpecies = new ArrayList<>();
        for (PestRecordSubmitRequest.SpeciesCount d : req.getDetails()) {
            String species = d.getPestSpecies().trim();
            PestRuleItem item = itemBySpecies.get(species);
            boolean over = item != null && d.getPestCount() > item.getThresholdCount();
            if (over) {
                overSpecies.add(species);
            }
            PestMonitorDetail detail = new PestMonitorDetail();
            detail.setPestSpecies(species);
            detail.setPestCount(d.getPestCount());
            detail.setThresholdCount(item == null ? null : item.getThresholdCount());
            detail.setOverThreshold(over ? 1 : 0);
            details.add(detail);
        }

        PestMonitorRecord record = new PestMonitorRecord();
        record.setRecordNo("PM-" + String.format("%06d", recordMapper.nextRecordNo()));
        record.setPointId(point.getId());
        record.setCardId(card.getId());
        // 规则版本快照：规则调整只生成新版本，不改写本记录旧结论
        record.setRuleVersionId(version.getId());
        record.setRuleVersionNo(version.getVersionNo());
        record.setMonitorMonth(month);
        record.setConclusion(overSpecies.isEmpty() ? PEST_CONCLUSION_NORMAL : PEST_CONCLUSION_OVER);
        record.setOverSpecies(overSpecies.isEmpty() ? null : String.join("、", overSpecies));
        record.setSubmittedBy(req.getSubmittedBy());
        record.setSubmitterRole(req.getSubmitterRole());
        record.setSubmittedAt(submittedAt);
        recordMapper.insert(record);
        for (PestMonitorDetail d : details) {
            d.setRecordId(record.getId());
            detailMapper.insert(d);
        }

        String roleName = PEST_ROLE_OUTSOURCE.equals(req.getSubmitterRole()) ? "外包人员" : "馆内人员";
        if (overSpecies.isEmpty()) {
            ledgerService.append(point.getId(), record.getId(), null,
                    version.getId(), version.getVersionNo(),
                    ACTION_PEST_RECORD_SUBMIT, record.getRecordNo(),
                    "点位 " + point.getPointNo() + "（" + point.getZoneName() + "）监测正常："
                            + "提交人 " + req.getSubmittedBy() + "（" + roleName + "），月份 " + month
                            + "，规则版本 " + version.getVersionNo(), submittedAt);
        } else {
            // 超阈值：生成处置任务并关联责任区域
            PestDisposeTask task = new PestDisposeTask();
            task.setTaskNo("PD-" + String.format("%06d", taskMapper.nextTaskNo()));
            task.setRecordId(record.getId());
            task.setPointId(point.getId());
            task.setResponsibleArea(point.getResponsibleArea());
            task.setOverSpecies(record.getOverSpecies());
            task.setRuleVersionId(version.getId());
            task.setRuleVersionNo(version.getVersionNo());
            task.setStatus(PEST_TASK_OPEN);
            taskMapper.insert(task);

            ledgerService.append(point.getId(), record.getId(), task.getId(),
                    version.getId(), version.getVersionNo(),
                    ACTION_PEST_RECORD_SUBMIT, record.getRecordNo(),
                    "点位 " + point.getPointNo() + "（" + point.getZoneName() + "）监测超阈值：虫种 "
                            + record.getOverSpecies() + "，提交人 " + req.getSubmittedBy()
                            + "（" + roleName + "），月份 " + month
                            + "，规则版本 " + version.getVersionNo(), submittedAt);
            ledgerService.append(point.getId(), record.getId(), task.getId(),
                    version.getId(), version.getVersionNo(),
                    ACTION_PEST_TASK_GENERATE, task.getTaskNo(),
                    "超阈值生成处置任务 " + task.getTaskNo() + "，关联责任区域【"
                            + point.getResponsibleArea() + "】，超阈值虫种 " + record.getOverSpecies(),
                    submittedAt);
        }
        return record;
    }

    // ================================================================
    // 现场照片（内容寻址；月份冻结后拒收）
    // ================================================================

    /**
     * 上传监测现场照片：复用影像归档内容寻址存储（同哈希只一份物理文件）。
     * 监测月份已冻结的记录不再接受补传；同一记录内同照片只登记一次。
     */
    @Transactional
    public PestMonitorPhoto uploadPhoto(Long recordId, MultipartFile file,
                                        String uploadedBy, String remark) {
        PestMonitorRecord record = requireRecord(recordId);
        requireMonthNotFrozen(record.getMonitorMonth());
        if (uploadedBy == null || uploadedBy.trim().isEmpty()) {
            throw new IllegalArgumentException("上传人不能为空");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("现场照片不能为空");
        }
        ImageStorage.StoredPhoto stored;
        try {
            // 现场照片不套用文物影像 800×800 归档下限，仅要求可解码
            stored = imageStorage.store(file.getBytes(), file.getOriginalFilename(),
                    file.getContentType(), false);
        } catch (IOException e) {
            throw new IllegalArgumentException("现场照片读取失败");
        }
        PhotoFile photoFile = obtainPhotoFile(stored, uploadedBy);

        PestMonitorPhoto exists = photoMapper.selectOne(new LambdaQueryWrapper<PestMonitorPhoto>()
                .eq(PestMonitorPhoto::getRecordId, recordId)
                .eq(PestMonitorPhoto::getContentHash, stored.hash));
        if (exists != null) {
            throw new IllegalArgumentException("该现场照片已关联监测记录 " + record.getRecordNo()
                    + "（内容一致）：" + (exists.getPhotoName() == null ? stored.hash : exists.getPhotoName()));
        }
        PestMonitorPhoto photo = new PestMonitorPhoto();
        photo.setRecordId(recordId);
        photo.setFileId(photoFile.getId());
        photo.setContentHash(stored.hash);
        photo.setPhotoName(stored.fileName);
        photo.setUploadedBy(uploadedBy.trim());
        photo.setRemark(remark);
        photoMapper.insert(photo);
        return photo;
    }

    private PhotoFile obtainPhotoFile(ImageStorage.StoredPhoto stored, String uploadedBy) {
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
        photoFileMapper.insert(photoFile);
        return photoFile;
    }

    // ================================================================
    // 处置任务关闭（外包人员不能关闭）
    // ================================================================

    /**
     * 关闭处置任务：仅馆内人员（INTERNAL）可关闭；
     * 外包人员只能提交现场数据，不能关闭任务。
     */
    @Transactional
    public PestDisposeTask closeTask(Long taskId, PestTaskCloseRequest req) {
        if (PEST_ROLE_OUTSOURCE.equals(req.getCloserRole())) {
            throw new IllegalArgumentException("外包人员只能提交现场数据，不能关闭处置任务");
        }
        if (!PEST_ROLE_INTERNAL.equals(req.getCloserRole())) {
            throw new IllegalArgumentException("关闭人角色只支持 INTERNAL 馆内人员 / OUTSOURCE 外包人员: "
                    + req.getCloserRole());
        }
        PestDisposeTask task = requireTask(taskId);
        if (PEST_TASK_CLOSED.equals(task.getStatus())) {
            throw new IllegalArgumentException("处置任务" + task.getTaskNo() + "已关闭，请勿重复关闭");
        }
        LocalDateTime now = LocalDateTime.now();
        task.setStatus(PEST_TASK_CLOSED);
        task.setClosedBy(req.getClosedBy());
        task.setClosedAt(now);
        task.setCloseNote(req.getCloseNote());
        taskMapper.updateById(task);

        PestTrapPoint point = pointService.requirePoint(task.getPointId());
        ledgerService.append(task.getPointId(), task.getRecordId(), task.getId(),
                task.getRuleVersionId(), task.getRuleVersionNo(),
                ACTION_PEST_TASK_CLOSE, task.getTaskNo(),
                "处置任务 " + task.getTaskNo() + "（点位 " + point.getPointNo() + "，责任区域【"
                        + task.getResponsibleArea() + "】）由馆内人员 " + req.getClosedBy()
                        + " 关闭" + (req.getCloseNote() == null ? "" : "：" + req.getCloseNote()), now);
        return task;
    }

    // ================================================================
    // 监测月份冻结（只追加不解冻）
    // ================================================================

    /**
     * 冻结监测月份：冻结后该月监测记录与照片不再接受补录/更改。
     * 重复冻结同一月份幂等，直接返回已冻结记录，不产生第二条。
     */
    @Transactional
    public PestMonthFreeze freezeMonth(PestMonthFreezeRequest req) {
        PestMonthFreeze existing = freezeMapper.selectOne(new LambdaQueryWrapper<PestMonthFreeze>()
                .eq(PestMonthFreeze::getMonitorMonth, req.getMonitorMonth())
                .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        LocalDateTime now = LocalDateTime.now();
        PestMonthFreeze freeze = new PestMonthFreeze();
        freeze.setMonitorMonth(req.getMonitorMonth());
        freeze.setFrozenBy(req.getFrozenBy());
        freeze.setFrozenAt(now);
        freezeMapper.insert(freeze);

        ledgerService.append(null, null, null, null, null,
                ACTION_PEST_MONTH_FREEZE, req.getMonitorMonth(),
                "监测月份 " + req.getMonitorMonth() + " 冻结（冻结人 " + req.getFrozenBy()
                        + "），该月监测记录与照片不再接受补录/更改", now);
        return freeze;
    }

    public boolean isMonthFrozen(String month) {
        return freezeMapper.selectCount(new LambdaQueryWrapper<PestMonthFreeze>()
                .eq(PestMonthFreeze::getMonitorMonth, month)) > 0;
    }

    private void requireMonthNotFrozen(String month) {
        if (isMonthFrozen(month)) {
            throw new IllegalArgumentException("监测月份 " + month + " 已冻结，监测结果不再接受补录/更改");
        }
    }

    // ================================================================
    // 查询（报表均标明规则版本）
    // ================================================================

    public PestMonitorRecord requireRecord(Long recordId) {
        PestMonitorRecord record = recordMapper.selectById(recordId);
        if (record == null) {
            throw new IllegalArgumentException("监测记录不存在: id=" + recordId);
        }
        return record;
    }

    public PestDisposeTask requireTask(Long taskId) {
        PestDisposeTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("处置任务不存在: id=" + taskId);
        }
        return task;
    }

    public List<PestMonitorRecord> listRecords(Long pointId, String month, String conclusion) {
        return recordMapper.selectList(new LambdaQueryWrapper<PestMonitorRecord>()
                .eq(pointId != null, PestMonitorRecord::getPointId, pointId)
                .eq(month != null && !month.isEmpty(), PestMonitorRecord::getMonitorMonth, month)
                .eq(conclusion != null && !conclusion.isEmpty(), PestMonitorRecord::getConclusion, conclusion)
                .orderByDesc(PestMonitorRecord::getSubmittedAt)
                .orderByDesc(PestMonitorRecord::getId));
    }

    public List<PestMonitorDetail> listDetails(Long recordId) {
        return detailMapper.selectList(new LambdaQueryWrapper<PestMonitorDetail>()
                .eq(PestMonitorDetail::getRecordId, recordId)
                .orderByAsc(PestMonitorDetail::getId));
    }

    public List<PestMonitorPhoto> listPhotos(Long recordId) {
        return photoMapper.selectList(new LambdaQueryWrapper<PestMonitorPhoto>()
                .eq(PestMonitorPhoto::getRecordId, recordId)
                .orderByAsc(PestMonitorPhoto::getId));
    }

    public List<PestDisposeTask> listTasks(Long pointId, String status, String responsibleArea) {
        return taskMapper.selectList(new LambdaQueryWrapper<PestDisposeTask>()
                .eq(pointId != null, PestDisposeTask::getPointId, pointId)
                .eq(status != null && !status.isEmpty(), PestDisposeTask::getStatus, status)
                .eq(responsibleArea != null && !responsibleArea.isEmpty(),
                        PestDisposeTask::getResponsibleArea, responsibleArea)
                .orderByDesc(PestDisposeTask::getId));
    }

    public List<PestMonthFreeze> listFreezes() {
        return freezeMapper.selectList(new LambdaQueryWrapper<PestMonthFreeze>()
                .orderByAsc(PestMonthFreeze::getMonitorMonth));
    }

    /** 监测记录报表：记录头（标明规则版本）+ 虫种明细（阈值快照）+ 现场照片 */
    public Map<String, Object> recordReport(Long recordId) {
        PestMonitorRecord record = requireRecord(recordId);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("record", record);
        report.put("ruleVersionId", record.getRuleVersionId());
        report.put("ruleVersionNo", record.getRuleVersionNo());
        report.put("details", listDetails(recordId));
        report.put("photos", listPhotos(recordId));
        return report;
    }
}
