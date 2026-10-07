package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.dto.InvDiffCreateRequest;
import com.evops.dto.InvDiffReviewRequest;
import com.evops.entity.InvDiffLine;
import com.evops.entity.InvDiffOrder;
import com.evops.entity.InvDiffPhoto;
import com.evops.entity.InvTask;
import com.evops.entity.PhotoFile;
import com.evops.mapper.InvDiffLineMapper;
import com.evops.mapper.InvDiffOrderMapper;
import com.evops.mapper.InvDiffPhotoMapper;
import com.evops.mapper.PhotoFileMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.evops.constant.MuseumConstants.*;

/**
 * 清点差异单：差异必须能解释——说明与见证人必填、现场照片复核前至少一张；
 * 复核完成前不能关闭展览；复核完成后差异单封存只读。
 */
@Service
public class InvDiffService {

    private final InvDiffOrderMapper diffOrderMapper;
    private final InvDiffLineMapper diffLineMapper;
    private final InvDiffPhotoMapper photoMapper;
    private final PhotoFileMapper photoFileMapper;
    private final InventoryService inventoryService;
    private final InvLedgerService ledgerService;
    private final ImageStorage imageStorage;

    public InvDiffService(InvDiffOrderMapper diffOrderMapper,
                          InvDiffLineMapper diffLineMapper,
                          InvDiffPhotoMapper photoMapper,
                          PhotoFileMapper photoFileMapper,
                          InventoryService inventoryService,
                          InvLedgerService ledgerService,
                          ImageStorage imageStorage) {
        this.diffOrderMapper = diffOrderMapper;
        this.diffLineMapper = diffLineMapper;
        this.photoMapper = photoMapper;
        this.photoFileMapper = photoFileMapper;
        this.inventoryService = inventoryService;
        this.ledgerService = ledgerService;
        this.imageStorage = imageStorage;
    }

    // ================================================================
    // 开立差异单（当前版本差异逐行固化；开立后该版本扫描封存）
    // ================================================================

    @Transactional
    public InvDiffOrder create(Long taskId, InvDiffCreateRequest req) {
        InvTask task = inventoryService.requireTask(taskId);
        if (!INV_TASK_OPEN.equals(task.getStatus())) {
            throw new IllegalArgumentException("清点任务 " + task.getTaskNo()
                    + " 已关闭，只能新增补充记录，不能重写原结果");
        }
        inventoryService.requireBaselineConsistent(task);
        int version = task.getBaselineVersion();
        if (diffOrderOfVersion(task.getId(), version) != null) {
            throw new IllegalArgumentException("基线版本 v" + version + " 已开立差异单，请勿重复开立");
        }
        if (req.getWitness2() != null && !req.getWitness2().trim().isEmpty()
                && req.getWitness1().trim().equals(req.getWitness2().trim())) {
            throw new IllegalArgumentException("两名见证人不得为同一人");
        }
        List<InventoryService.DiffEntry> diffs = inventoryService.computeDifferences(task);
        if (diffs.isEmpty()) {
            throw new IllegalArgumentException("当前基线版本 v" + version + " 无差异，无需开立差异单");
        }

        InvDiffOrder order = new InvDiffOrder();
        order.setDiffNo("DIF-" + String.format("%06d", diffOrderMapper.nextDiffNo()));
        order.setTaskId(task.getId());
        order.setBaselineVersion(version);
        order.setMissingCount((int) diffs.stream().filter(d -> INV_DIFF_MISSING.equals(d.type)).count());
        order.setMisplacedCount((int) diffs.stream().filter(d -> INV_DIFF_MISPLACED.equals(d.type)).count());
        order.setAbnormalCount((int) diffs.stream().filter(d -> INV_DIFF_ABNORMAL.equals(d.type)).count());
        order.setExplanation(req.getExplanation().trim());
        order.setWitness1(req.getWitness1().trim());
        order.setWitness2(req.getWitness2() == null || req.getWitness2().trim().isEmpty()
                ? null : req.getWitness2().trim());
        order.setStatus(INV_DIFF_OPEN);
        order.setCreatedBy(req.getCreatedBy());
        diffOrderMapper.insert(order);

        for (InventoryService.DiffEntry d : diffs) {
            InvDiffLine line = new InvDiffLine();
            line.setDiffId(order.getId());
            line.setDiffType(d.type);
            line.setRelicId(d.relicId);
            line.setRelicNo(d.relicNo);
            line.setDisplayNo(d.displayNo);
            line.setBoxNoExpected(d.boxNoExpected);
            line.setBoxNoActual(d.boxNoActual);
            line.setPointCode(d.pointCode);
            line.setDetail(d.detail);
            diffLineMapper.insert(line);
        }
        ledgerService.append(task.getHallId(), task.getId(), order.getId(),
                ACTION_INV_DIFF_CREATE, order.getDiffNo(),
                "开立差异单 " + order.getDiffNo() + "（基线版本 v" + version + "）：缺失 "
                        + order.getMissingCount() + "，错位 " + order.getMisplacedCount()
                        + "，状态异常 " + order.getAbnormalCount() + "，见证人 " + order.getWitness1()
                        + (order.getWitness2() == null ? "" : "、" + order.getWitness2())
                        + "，说明：" + order.getExplanation(),
                LocalDateTime.now());
        return order;
    }

    // ================================================================
    // 现场照片（内容寻址；复核前至少一张；复核完成后封存）
    // ================================================================

    @Transactional
    public InvDiffPhoto uploadPhoto(Long diffId, MultipartFile file, String uploadedBy, String remark) {
        InvDiffOrder order = requireDiff(diffId);
        if (!INV_DIFF_OPEN.equals(order.getStatus())) {
            throw new IllegalArgumentException("差异单 " + order.getDiffNo() + " 已复核完成，照片封存只读");
        }
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

        InvDiffPhoto exists = photoMapper.selectOne(new LambdaQueryWrapper<InvDiffPhoto>()
                .eq(InvDiffPhoto::getDiffId, diffId)
                .eq(InvDiffPhoto::getContentHash, stored.hash));
        if (exists != null) {
            throw new IllegalArgumentException("该现场照片已关联差异单 " + order.getDiffNo()
                    + "（内容一致）：" + (exists.getPhotoName() == null ? stored.hash : exists.getPhotoName()));
        }
        InvDiffPhoto photo = new InvDiffPhoto();
        photo.setDiffId(diffId);
        photo.setFileId(photoFile.getId());
        photo.setContentHash(stored.hash);
        photo.setPhotoName(stored.fileName);
        photo.setUploadedBy(uploadedBy.trim());
        photo.setRemark(remark);
        photoMapper.insert(photo);
        return photo;
    }

    // ================================================================
    // 复核（复核完成前不能关闭展览；退回须填意见）
    // ================================================================

    @Transactional
    public InvDiffOrder review(Long diffId, InvDiffReviewRequest req) {
        InvDiffOrder order = requireDiff(diffId);
        if (!INV_DIFF_OPEN.equals(order.getStatus())) {
            throw new IllegalArgumentException("差异单 " + order.getDiffNo() + " 已复核完成，请勿重复复核");
        }
        InvTask task = inventoryService.requireTask(order.getTaskId());
        Long photoCount = photoMapper.selectCount(new LambdaQueryWrapper<InvDiffPhoto>()
                .eq(InvDiffPhoto::getDiffId, diffId));
        if (photoCount == null || photoCount == 0) {
            throw new IllegalArgumentException("差异单 " + order.getDiffNo()
                    + " 尚未关联现场照片，不能复核（差异单要引用现场照片与见证人）");
        }
        LocalDateTime now = LocalDateTime.now();
        if (Boolean.TRUE.equals(req.getApproved())) {
            order.setStatus(INV_DIFF_REVIEWED);
            order.setReviewedBy(req.getReviewedBy().trim());
            order.setReviewedAt(now);
            order.setReviewNote(req.getNote());
            diffOrderMapper.updateById(order);
            ledgerService.append(task.getHallId(), task.getId(), order.getId(),
                    ACTION_INV_DIFF_REVIEW, order.getDiffNo(),
                    "差异单复核通过，复核人：" + req.getReviewedBy()
                            + (req.getNote() == null ? "" : "，意见：" + req.getNote()), now);
        } else {
            if (req.getNote() == null || req.getNote().trim().isEmpty()) {
                throw new IllegalArgumentException("复核退回必须填写意见");
            }
            order.setReviewNote(req.getNote().trim());
            diffOrderMapper.updateById(order);
            ledgerService.append(task.getHallId(), task.getId(), order.getId(),
                    ACTION_INV_DIFF_REVIEW_REJECT, order.getDiffNo(),
                    "差异单复核退回，复核人：" + req.getReviewedBy() + "，意见：" + req.getNote().trim(), now);
        }
        return order;
    }

    // ================================================================
    // 查询
    // ================================================================

    public InvDiffOrder requireDiff(Long diffId) {
        InvDiffOrder order = diffOrderMapper.selectById(diffId);
        if (order == null) {
            throw new IllegalArgumentException("差异单不存在: id=" + diffId);
        }
        return order;
    }

    public List<InvDiffOrder> listDiffs(Long taskId, String status) {
        return diffOrderMapper.selectList(new LambdaQueryWrapper<InvDiffOrder>()
                .eq(taskId != null, InvDiffOrder::getTaskId, taskId)
                .eq(status != null && !status.isEmpty(), InvDiffOrder::getStatus, status)
                .orderByAsc(InvDiffOrder::getId));
    }

    /** 差异单详情：单头 + 逐行差异 + 现场照片 */
    public Map<String, Object> detail(Long diffId) {
        InvDiffOrder order = requireDiff(diffId);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("order", order);
        detail.put("lines", diffLineMapper.selectList(new LambdaQueryWrapper<InvDiffLine>()
                .eq(InvDiffLine::getDiffId, diffId)
                .orderByAsc(InvDiffLine::getId)));
        detail.put("photos", photoMapper.selectList(new LambdaQueryWrapper<InvDiffPhoto>()
                .eq(InvDiffPhoto::getDiffId, diffId)
                .orderByAsc(InvDiffPhoto::getId)));
        return detail;
    }

    private InvDiffOrder diffOrderOfVersion(Long taskId, int version) {
        return diffOrderMapper.selectOne(new LambdaQueryWrapper<InvDiffOrder>()
                .eq(InvDiffOrder::getTaskId, taskId)
                .eq(InvDiffOrder::getBaselineVersion, version));
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
}
