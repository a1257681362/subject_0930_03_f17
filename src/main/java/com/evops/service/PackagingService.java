package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.dto.PackSpecPublishRequest;
import com.evops.dto.PackUnpackRequest;
import com.evops.dto.SpecItemDto;
import com.evops.dto.SubstituteApproveRequest;
import com.evops.dto.SubstitutePackRequest;
import com.evops.dto.PackCreateRequest;
import com.evops.entity.LoanAgreement;
import com.evops.entity.PackNotifyOutbox;
import com.evops.entity.PackOrder;
import com.evops.entity.PackPhoto;
import com.evops.entity.PackSpecItem;
import com.evops.entity.PackSpecVersion;
import com.evops.entity.PackSubstitute;
import com.evops.entity.PhotoFile;
import com.evops.entity.Relic;
import com.evops.entity.RelicIntake;
import com.evops.gateway.MuseumRepairUnavailableException;
import com.evops.mapper.LoanAgreementMapper;
import com.evops.mapper.PackNotifyOutboxMapper;
import com.evops.mapper.PackOrderMapper;
import com.evops.mapper.PackPhotoMapper;
import com.evops.mapper.PackSpecItemMapper;
import com.evops.mapper.PackSpecVersionMapper;
import com.evops.mapper.PackSubstituteMapper;
import com.evops.mapper.PhotoFileMapper;
import com.evops.mapper.RelicIntakeMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.evops.constant.MuseumConstants.*;

/**
 * 文物运输包装规格核心服务（museum-13）。
 *
 * <ol>
 *   <li><b>规格版本化</b>：按材质/尺寸档/缓冲等级/气候区发布版本，旧版 SUPERSEDED
 *       只追加；装箱单固化版本与条目全量快照——规格变更不影响已签押运单。</li>
 *   <li><b>装箱校验</b>：按 材质+气候区+缓冲等级+外形区间 定位条目，逐项校验
 *       文物/装箱重量、外形与防震（缓冲等级）要求，不满足一律拒绝。</li>
 *   <li><b>临时替代</b>：必须审批，替代能力与对标准规格的逐项差异随审批单固化；
 *       批准后才进入外部确认，驳回装箱单终态 REJECTED。</li>
 *   <li><b>照片绑箱号</b>：发运前/拆箱后两阶段只追加；拆箱只回写拆箱字段，
 *       发运前证据永不覆盖。</li>
 *   <li><b>外部依赖不假装成功</b>：通知无响应时装箱单停留 NOTIFY_PENDING，
 *       行级认领 + 补偿调度保证恢复后恰好通知一次。</li>
 * </ol>
 */
@Service
public class PackagingService {

    private final PackSpecVersionMapper versionMapper;
    private final PackSpecItemMapper itemMapper;
    private final PackOrderMapper orderMapper;
    private final PackSubstituteMapper substituteMapper;
    private final PackPhotoMapper photoMapper;
    private final PhotoFileMapper photoFileMapper;
    private final PackNotifyOutboxMapper outboxMapper;
    private final RelicIntakeMapper intakeMapper;
    private final LoanAgreementMapper agreementMapper;
    private final RelicService relicService;
    private final PackNotifyService notifyService;
    private final PackLedgerService ledgerService;
    private final ImageStorage imageStorage;
    private final TransactionTemplate tx;

    public PackagingService(PackSpecVersionMapper versionMapper,
                            PackSpecItemMapper itemMapper,
                            PackOrderMapper orderMapper,
                            PackSubstituteMapper substituteMapper,
                            PackPhotoMapper photoMapper,
                            PhotoFileMapper photoFileMapper,
                            PackNotifyOutboxMapper outboxMapper,
                            RelicIntakeMapper intakeMapper,
                            LoanAgreementMapper agreementMapper,
                            RelicService relicService,
                            PackNotifyService notifyService,
                            PackLedgerService ledgerService,
                            ImageStorage imageStorage,
                            PlatformTransactionManager transactionManager) {
        this.versionMapper = versionMapper;
        this.itemMapper = itemMapper;
        this.orderMapper = orderMapper;
        this.substituteMapper = substituteMapper;
        this.photoMapper = photoMapper;
        this.photoFileMapper = photoFileMapper;
        this.outboxMapper = outboxMapper;
        this.intakeMapper = intakeMapper;
        this.agreementMapper = agreementMapper;
        this.relicService = relicService;
        this.notifyService = notifyService;
        this.ledgerService = ledgerService;
        this.imageStorage = imageStorage;
        this.tx = new TransactionTemplate(transactionManager);
    }

    // ================================================================
    // 规格版本发布
    // ================================================================

    /** 发布包装规格新版本：旧 ACTIVE 置 SUPERSEDED（只追加），条目随版本固化。 */
    @Transactional
    public PackSpecVersion publishSpec(PackSpecPublishRequest req) {
        if (versionMapper.selectCount(new LambdaQueryWrapper<PackSpecVersion>()
                .eq(PackSpecVersion::getVersionNo, req.getVersionNo())) > 0) {
            throw new IllegalArgumentException("包装规格版本号已存在: " + req.getVersionNo());
        }
        if (req.getItems() == null || req.getItems().isEmpty()) {
            throw new IllegalArgumentException("包装规格条目不能为空");
        }
        // 同版本内 材质+尺寸档+缓冲等级+气候区 唯一
        List<String> keys = new ArrayList<>();
        for (SpecItemDto dto : req.getItems()) {
            validateItemRanges(dto);
            String key = dto.getMaterial().trim() + "|" + dto.getSizeCode().trim()
                    + "|" + dto.getCushionLevel().trim() + "|" + dto.getClimateZone().trim();
            if (!keys.add(key)) {
                throw new IllegalArgumentException("同版本内规格条目重复: " + key);
            }
        }

        LocalDateTime now = LocalDateTime.now();
        List<PackSpecVersion> actives = versionMapper.selectList(new LambdaQueryWrapper<PackSpecVersion>()
                .eq(PackSpecVersion::getStatus, PACK_SPEC_ACTIVE));
        for (PackSpecVersion old : actives) {
            old.setStatus(PACK_SPEC_SUPERSEDED);
            versionMapper.updateById(old);
        }

        PackSpecVersion version = new PackSpecVersion();
        version.setVersionNo(req.getVersionNo().trim());
        version.setTitle(req.getTitle());
        version.setRemark(req.getRemark());
        version.setStatus(PACK_SPEC_ACTIVE);
        version.setPublishedBy(req.getPublishedBy());
        version.setPublishedAt(now);
        versionMapper.insert(version);

        for (SpecItemDto dto : req.getItems()) {
            PackSpecItem item = new PackSpecItem();
            item.setSpecVersionId(version.getId());
            item.setMaterial(dto.getMaterial().trim());
            item.setSizeCode(dto.getSizeCode().trim());
            item.setFitLengthMinMm(dto.getFitLengthMinMm());
            item.setFitLengthMaxMm(dto.getFitLengthMaxMm());
            item.setFitWidthMinMm(dto.getFitWidthMinMm());
            item.setFitWidthMaxMm(dto.getFitWidthMaxMm());
            item.setFitDepthMinMm(dto.getFitDepthMinMm());
            item.setFitDepthMaxMm(dto.getFitDepthMaxMm());
            item.setMaxWeightG(dto.getMaxWeightG());
            item.setCushionLevel(dto.getCushionLevel().trim());
            item.setShockLimitG(dto.getShockLimitG());
            item.setClimateZone(dto.getClimateZone().trim());
            itemMapper.insert(item);
        }

        ledgerService.append(null, null, null, version.getId(), version.getVersionNo(),
                ACTION_PACK_SPEC_PUBLISH, version.getVersionNo(),
                "包装规格 " + version.getVersionNo() + " 发布（" + req.getItems().size()
                        + " 条，按材质/尺寸/缓冲等级/气候区配置），旧生效版本置 SUPERSEDED", now);
        return version;
    }

    private void validateItemRanges(SpecItemDto d) {
        if (d.getFitLengthMinMm().compareTo(d.getFitLengthMaxMm()) > 0
                || d.getFitWidthMinMm().compareTo(d.getFitWidthMaxMm()) > 0
                || d.getFitDepthMinMm().compareTo(d.getFitDepthMaxMm()) > 0) {
            throw new IllegalArgumentException("规格条目外形区间下限不能大于上限: " + d.getSizeCode());
        }
    }

    public PackSpecVersion requireActiveVersion() {
        PackSpecVersion version = versionMapper.selectOne(new LambdaQueryWrapper<PackSpecVersion>()
                .eq(PackSpecVersion::getStatus, PACK_SPEC_ACTIVE)
                .orderByDesc(PackSpecVersion::getId).last("LIMIT 1"));
        if (version == null) {
            throw new IllegalArgumentException("尚无生效包装规格版本，请先发布包装规格");
        }
        return version;
    }

    public List<PackSpecVersion> listVersions() {
        return versionMapper.selectList(new LambdaQueryWrapper<PackSpecVersion>()
                .orderByDesc(PackSpecVersion::getId));
    }

    public Map<String, Object> versionDetail(Long versionId) {
        PackSpecVersion version = versionMapper.selectById(versionId);
        if (version == null) {
            throw new IllegalArgumentException("包装规格版本不存在: id=" + versionId);
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("version", version);
        detail.put("items", itemMapper.selectList(new LambdaQueryWrapper<PackSpecItem>()
                .eq(PackSpecItem::getSpecVersionId, versionId)
                .orderByAsc(PackSpecItem::getId)));
        return detail;
    }

    // ================================================================
    // 标准装箱（校验重量/外形/防震 + 固化快照 + 外部确认）
    // ================================================================

    /**
     * 按规格装箱。先在事务内落装箱单（规格快照）与外部通知记录并提交；
     * 提交后再通知外部修复依赖——无响应则装箱单停留 NOTIFY_PENDING，绝不假装成功。
     */
    public Map<String, Object> packStandard(PackCreateRequest req) {
        PackOrder existing = findByIdempotentRequest(req.getRequestNo());
        if (existing != null) {
            // 幂等回放：原样回读，不再投递、不二次执行
            return orderView(existing, null);
        }
        PackOrder order = tx.execute(status -> persistStandard(req));
        return afterPersistNotify(order);
    }

    private PackOrder persistStandard(PackCreateRequest req) {
        PackContext ctx = prepareContext(req);
        PackSpecItem item = matchStandardItem(ctx);

        LocalDateTime now = req.getSealedAt() == null ? LocalDateTime.now() : req.getSealedAt();
        PackOrder order = newOrder(req, ctx, PACK_STANDARD, now);
        snapshotStandard(order, item);
        order.setStatus(PACK_ORDER_NOTIFY_PENDING);
        orderMapper.insert(order);

        createOutbox(order);
        ledgerService.append(order.getRelicId(), order.getId(), null,
                order.getSpecVersionId(), order.getSpecVersionNo(),
                ACTION_PACK_STANDARD, order.getOrderNo(),
                "按规格装箱 " + order.getOrderNo() + "，箱号 " + order.getBoxNo()
                        + "，规格 " + order.getSpecVersionNo() + "/" + item.getSizeCode()
                        + "，实测重量 " + order.getActualWeightG() + "g、外形 "
                        + order.getActualLengthMm() + "×" + order.getActualWidthMm() + "×"
                        + order.getActualDepthMm() + "mm，缓冲 " + order.getSpecCushionLevel()
                        + "，待外部修复依赖确认", now);
        return order;
    }

    // ================================================================
    // 临时替代包装：申请 + 审批
    // ================================================================

    /** 临时替代装箱申请：校验后落 PENDING_APPROVAL 装箱单与替代审批单（含逐项差异）。 */
    @Transactional
    public PackOrder applySubstitute(SubstitutePackRequest req) {
        PackOrder existing = findByIdempotentRequest(req.getRequestNo());
        if (existing != null) {
            return existing;
        }
        PackContext ctx = prepareContext(req);
        // 标准条目仍须定位：差异对照与装箱单标准规格快照都锚定它
        PackSpecItem standard = matchStandardItem(ctx);
        validateSubstituteCapability(req, ctx);

        LocalDateTime now = req.getSealedAt() == null ? LocalDateTime.now() : req.getSealedAt();
        PackOrder order = newOrder(req, ctx, PACK_SUBSTITUTE, now);
        snapshotStandard(order, standard);
        order.setStatus(PACK_ORDER_PENDING_APPROVAL);
        orderMapper.insert(order);

        PackSubstitute sub = new PackSubstitute();
        sub.setSubstituteNo("PS-" + String.format("%06d", substituteMapper.nextSubstituteNo()));
        sub.setOrderId(order.getId());
        sub.setReason(req.getReason().trim());
        sub.setApplicant(req.getApplicant().trim());
        sub.setSubSizeCode(req.getSubSizeCode().trim());
        sub.setSubCushionLevel(req.getSubCushionLevel().trim());
        sub.setSubClimateZone(req.getSubClimateZone().trim());
        sub.setSubMaxWeightG(req.getSubMaxWeightG());
        sub.setSubShockLimitG(req.getSubShockLimitG());
        sub.setSubFitLengthMinMm(req.getSubFitLengthMinMm());
        sub.setSubFitLengthMaxMm(req.getSubFitLengthMaxMm());
        sub.setSubFitWidthMinMm(req.getSubFitWidthMinMm());
        sub.setSubFitWidthMaxMm(req.getSubFitWidthMaxMm());
        sub.setSubFitDepthMinMm(req.getSubFitDepthMinMm());
        sub.setSubFitDepthMaxMm(req.getSubFitDepthMaxMm());
        fillDiffs(sub, standard, req, ctx);
        sub.setStatus(SUB_PENDING);
        substituteMapper.insert(sub);

        ledgerService.append(order.getRelicId(), order.getId(), sub.getId(),
                order.getSpecVersionId(), order.getSpecVersionNo(),
                ACTION_PACK_SUBSTITUTE_APPLY, order.getOrderNo(),
                "临时替代包装申请 " + sub.getSubstituteNo() + "（箱号 " + order.getBoxNo()
                        + "），原因：" + req.getReason() + "；与标准规格差异："
                        + sub.getDiffDetail() + "；待审批，审批前不得发运", now);
        return order;
    }

    /**
     * 替代包装审批：通过 → 装箱单进入外部确认（建通知记录并尝试投递，无响应则停留）；
     * 驳回 → 装箱单终态 REJECTED。
     */
    public Map<String, Object> approveSubstitute(Long substituteId, SubstituteApproveRequest req) {
        PackOrder order = tx.execute(status -> persistApproval(substituteId, req));
        if (PACK_ORDER_REJECTED.equals(order.getStatus())) {
            // 驳回：装箱单终态，不产生外部通知
            return orderView(order, false);
        }
        // 批准：进入外部修复依赖确认（无响应则停留 NOTIFY_PENDING，不假装成功）
        return afterPersistNotify(order);
    }

    private PackOrder persistApproval(Long substituteId, SubstituteApproveRequest req) {
        PackSubstitute sub = substituteMapper.selectById(substituteId);
        if (sub == null) {
            throw new IllegalArgumentException("替代包装审批单不存在: id=" + substituteId);
        }
        if (!SUB_PENDING.equals(sub.getStatus())) {
            throw new IllegalArgumentException("替代包装已审批，不能重复审批: " + sub.getSubstituteNo());
        }
        PackOrder order = requireOrder(sub.getOrderId());
        LocalDateTime now = LocalDateTime.now();
        boolean approved = Boolean.TRUE.equals(req.getApproved());
        if (!approved && (req.getOpinion() == null || req.getOpinion().trim().isEmpty())) {
            throw new IllegalArgumentException("驳回临时替代包装必须填写审批意见");
        }

        sub.setApprover(req.getApprover().trim());
        sub.setApproved(approved ? 1 : 0);
        sub.setApprovedAt(now);
        sub.setApprovalOpinion(req.getOpinion());

        if (approved) {
            sub.setStatus(SUB_APPROVED);
            substituteMapper.updateById(sub);
            order.setStatus(PACK_ORDER_NOTIFY_PENDING);
            orderMapper.updateById(order);
            createOutbox(order);
            ledgerService.append(order.getRelicId(), order.getId(), sub.getId(),
                    order.getSpecVersionId(), order.getSpecVersionNo(),
                    ACTION_PACK_SUBSTITUTE_APPROVE, order.getOrderNo(),
                    "临时替代包装 " + sub.getSubstituteNo() + " 经 " + req.getApprover()
                            + " 批准，差异已记录留痕；装箱单进入外部修复依赖确认", now);
        } else {
            sub.setStatus(SUB_REJECTED);
            substituteMapper.updateById(sub);
            order.setStatus(PACK_ORDER_REJECTED);
            orderMapper.updateById(order);
            ledgerService.append(order.getRelicId(), order.getId(), sub.getId(),
                    order.getSpecVersionId(), order.getSpecVersionNo(),
                    ACTION_PACK_SUBSTITUTE_REJECT, order.getOrderNo(),
                    "临时替代包装 " + sub.getSubstituteNo() + " 经 " + req.getApprover()
                            + " 驳回：" + req.getOpinion() + "；装箱单置 REJECTED，不得发运", now);
        }
        return order;
    }

    // ================================================================
    // 装箱照片（与箱号绑定，两阶段只追加）
    // ================================================================

    /**
     * 上传装箱照片：与箱号绑定。
     * BEFORE_SHIP 仅 NOTIFY_PENDING/PACKED 可传（发运前证据）；
     * AFTER_UNPACK 仅 UNPACKED 可传（拆箱后证据）。同阶段同内容只登记一次。
     */
    @Transactional
    public PackPhoto uploadPhoto(Long orderId, String stage, MultipartFile file,
                                 String uploadedBy, String remark) {
        PackOrder order = requireOrder(orderId);
        if (!PACK_PHOTO_BEFORE_SHIP.equals(stage) && !PACK_PHOTO_AFTER_UNPACK.equals(stage)) {
            throw new IllegalArgumentException("照片阶段只能为 BEFORE_SHIP/AFTER_UNPACK");
        }
        if (uploadedBy == null || uploadedBy.trim().isEmpty()) {
            throw new IllegalArgumentException("上传人不能为空");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("装箱照片不能为空");
        }
        if (PACK_PHOTO_BEFORE_SHIP.equals(stage)
                && !PACK_ORDER_NOTIFY_PENDING.equals(order.getStatus())
                && !PACK_ORDER_PACKED.equals(order.getStatus())) {
            throw new IllegalArgumentException("发运前照片只能在装箱后、拆箱前上传（当前状态："
                    + order.getStatus() + "）");
        }
        if (PACK_PHOTO_AFTER_UNPACK.equals(stage)
                && !PACK_ORDER_UNPACKED.equals(order.getStatus())) {
            throw new IllegalArgumentException("拆箱后照片只能在拆箱回写后上传（当前状态："
                    + order.getStatus() + "）");
        }

        ImageStorage.StoredPhoto stored;
        try {
            // 现场照片不套文物影像 800×800 归档下限，仅要求可解码
            stored = imageStorage.store(file.getBytes(), file.getOriginalFilename(),
                    file.getContentType(), false);
        } catch (IOException e) {
            throw new IllegalArgumentException("装箱照片读取失败");
        }
        if (photoMapper.selectCount(new LambdaQueryWrapper<PackPhoto>()
                .eq(PackPhoto::getOrderId, orderId)
                .eq(PackPhoto::getPhotoStage, stage)
                .eq(PackPhoto::getContentHash, stored.hash)) > 0) {
            throw new IllegalArgumentException("该照片已关联箱号 " + order.getBoxNo() + " 的"
                    + (PACK_PHOTO_BEFORE_SHIP.equals(stage) ? "发运前" : "拆箱后") + "证据（内容一致）");
        }
        PhotoFile photoFile = obtainPhotoFile(stored, uploadedBy);

        PackPhoto photo = new PackPhoto();
        photo.setOrderId(orderId);
        photo.setBoxNo(order.getBoxNo());
        photo.setFileId(photoFile.getId());
        photo.setContentHash(stored.hash);
        photo.setPhotoName(stored.fileName);
        photo.setPhotoStage(stage);
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
        photoFile.setPhotographer(uploadBy(uploadedBy));
        photoFile.setRefCount(1);
        photoFileMapper.insert(photoFile);
        return photoFile;
    }

    private String uploadBy(String uploadedBy) {
        return uploadedBy.trim();
    }

    // ================================================================
    // 拆箱回写（只写拆箱字段，不覆盖发运前证据）
    // ================================================================

    /**
     * 拆箱包装状态回写：仅 PACKED（外部已确认）可拆；拆箱前必须有至少一张发运前照片。
     * 只回写 unpack_* 列并另传 AFTER_UNPACK 照片，发运前快照与 BEFORE_SHIP 照片永不覆盖。
     */
    @Transactional
    public PackOrder unpack(Long orderId, PackUnpackRequest req) {
        PackOrder order = requireOrder(orderId);
        if (PACK_ORDER_UNPACKED.equals(order.getStatus())) {
            throw new IllegalArgumentException("该箱已拆箱并回写，不能重复拆箱: " + order.getBoxNo());
        }
        if (!PACK_ORDER_PACKED.equals(order.getStatus())) {
            throw new IllegalArgumentException("装箱尚未经外部修复依赖确认（" + order.getStatus()
                    + "），不能拆箱回写: " + order.getBoxNo());
        }
        if (PACK_ORDER_REJECTED.equals(order.getStatus())) {
            throw new IllegalArgumentException("替代包装被驳回的装箱单不能拆箱: " + order.getBoxNo());
        }
        if (countPhotos(orderId, PACK_PHOTO_BEFORE_SHIP) == 0) {
            throw new IllegalArgumentException("拆箱前缺少与箱号 " + order.getBoxNo()
                    + " 绑定的发运前装箱照片，不能拆箱回写");
        }
        if (UNPACK_DAMAGED.equals(req.getCondition())
                && (req.getNote() == null || req.getNote().trim().isEmpty())) {
            throw new IllegalArgumentException("包装状态为受损 DAMAGED 时必须填写情况说明");
        }

        LocalDateTime now = req.getUnpackedAt() == null ? LocalDateTime.now() : req.getUnpackedAt();
        // 仅回写拆箱列；spec 快照、actual_*、sealed_* 发运前证据一概不触碰
        order.setUnpackedBy(req.getUnpackedBy().trim());
        order.setUnpackedAt(now);
        order.setUnpackCondition(req.getCondition());
        order.setUnpackNote(req.getNote());
        order.setStatus(PACK_ORDER_UNPACKED);
        orderMapper.updateById(order);

        ledgerService.append(order.getRelicId(), order.getId(), null,
                order.getSpecVersionId(), order.getSpecVersionNo(),
                ACTION_PACK_UNPACK, order.getOrderNo(),
                "拆箱回写箱号 " + order.getBoxNo() + "，包装状态："
                        + (UNPACK_INTACT.equals(req.getCondition()) ? "完好" : "受损")
                        + "，拆箱人 " + req.getUnpackedBy()
                        + "；发运前规格快照与装箱照片保持不变（不覆盖发运前证据）", now);
        return order;
    }

    // ================================================================
    // 外部通知手动重试（恢复后不重复通知由行级认领保证）
    // ================================================================

    /** 手动补偿投递某装箱单的外部通知；外部无响应时抛错且装箱单保持 NOTIFY_PENDING。 */
    public Map<String, Object> retryNotify(Long orderId) {
        PackOrder order = requireOrder(orderId);
        if (PACK_ORDER_PACKED.equals(order.getStatus()) || PACK_ORDER_UNPACKED.equals(order.getStatus())) {
            return orderView(order, true);
        }
        if (!PACK_ORDER_NOTIFY_PENDING.equals(order.getStatus())) {
            throw new IllegalArgumentException("装箱单当前状态 " + order.getStatus() + "，无待送达外部通知");
        }
        notifyService.deliverOrder(orderId);
        return orderView(requireOrder(orderId), true);
    }

    // ================================================================
    // 查询
    // ================================================================

    public PackOrder requireOrder(Long id) {
        PackOrder order = orderMapper.selectById(id);
        if (order == null) {
            throw new IllegalArgumentException("装箱单不存在: id=" + id);
        }
        return order;
    }

    public List<PackOrder> listOrders(Long relicId, Long agreementId, String status, String boxNo) {
        return orderMapper.selectList(new LambdaQueryWrapper<PackOrder>()
                .eq(relicId != null, PackOrder::getRelicId, relicId)
                .eq(agreementId != null, PackOrder::getAgreementId, agreementId)
                .eq(status != null && !status.isEmpty(), PackOrder::getStatus, status)
                .eq(boxNo != null && !boxNo.isEmpty(), PackOrder::getBoxNo, boxNo)
                .orderByDesc(PackOrder::getId));
    }

    /** 装箱单详情：单头 + 替代审批 + 分阶段照片 + 外部通知状态 */
    public Map<String, Object> detail(Long orderId) {
        PackOrder order = requireOrder(orderId);
        Map<String, Object> detail = orderView(order, null);
        PackSubstitute sub = substituteMapper.selectOne(new LambdaQueryWrapper<PackSubstitute>()
                .eq(PackSubstitute::getOrderId, orderId).last("LIMIT 1"));
        detail.put("substitute", sub);
        detail.put("beforeShipPhotos", listPhotos(orderId, PACK_PHOTO_BEFORE_SHIP));
        detail.put("afterUnpackPhotos", listPhotos(orderId, PACK_PHOTO_AFTER_UNPACK));
        return detail;
    }

    // ================================================================
    // 内部辅助
    // ================================================================

    /** 装箱公共上下文：文物、押运单、气候区、防震要求、入藏重量/外形。 */
    private PackContext prepareContext(PackCreateRequest req) {
        Relic relic = relicService.requireActiveRelic(req.getRelicId());
        LoanAgreement agreement = agreementMapper.selectById(req.getAgreementId());
        if (agreement == null) {
            throw new IllegalArgumentException("押运单（借展协议）不存在: id=" + req.getAgreementId());
        }
        if (!agreement.getRelicId().equals(relic.getId())) {
            throw new IllegalArgumentException("押运单与文物不匹配：押运单不属于文物 " + relic.getRelicNo());
        }
        if (!AGR_SIGNED.equals(agreement.getStatus()) && !AGR_IN_TRANSIT.equals(agreement.getStatus())) {
            throw new IllegalArgumentException("押运单未签署或已闭环，不能装箱: "
                    + agreement.getAgreementNo() + "（" + agreement.getStatus() + "）");
        }
        // 同一押运单同一文物只允许一张有效（非驳回）装箱单
        long activeCount = orderMapper.selectCount(new LambdaQueryWrapper<PackOrder>()
                .eq(PackOrder::getAgreementId, req.getAgreementId())
                .eq(PackOrder::getRelicId, relic.getId())
                .ne(PackOrder::getStatus, PACK_ORDER_REJECTED));
        if (activeCount > 0) {
            throw new IllegalArgumentException("该押运单已存在有效装箱单，不能重复装箱: "
                    + agreement.getAgreementNo());
        }
        if (orderMapper.selectCount(new LambdaQueryWrapper<PackOrder>()
                .eq(PackOrder::getBoxNo, req.getBoxNo().trim())) > 0) {
            throw new IllegalArgumentException("箱号已被占用: " + req.getBoxNo());
        }

        RelicIntake intake = intakeMapper.selectOne(new LambdaQueryWrapper<RelicIntake>()
                .eq(RelicIntake::getRelicId, relic.getId()).last("LIMIT 1"));

        PackContext ctx = new PackContext();
        ctx.relic = relic;
        ctx.agreement = agreement;
        ctx.climateZone = req.getClimateZone().trim();
        ctx.requiredCushion = req.getRequiredCushionLevel() == null
                || req.getRequiredCushionLevel().trim().isEmpty()
                ? defaultCushion(relic.getPreservationGrade())
                : req.getRequiredCushionLevel().trim();
        ctx.intake = intake;
        ctx.reqWeight = req.getActualWeightG();
        ctx.reqLength = req.getActualLengthMm();
        ctx.reqWidth = req.getActualWidthMm();
        ctx.reqDepth = req.getActualDepthMm();
        return ctx;
    }

    /** 防震要求缺省按文物保存等级：一级 L1、二级 L2、三级 L3 */
    private String defaultCushion(String grade) {
        if (GRADE_ONE.equals(grade)) {
            return CUSHION_L1;
        }
        if (GRADE_TWO.equals(grade)) {
            return CUSHION_L2;
        }
        return CUSHION_L3;
    }

    /** 在生效版本中按 材质+气候区+外形区间 定位候选，并校验重量/外形/防震。 */
    private PackSpecItem matchStandardItem(PackContext ctx) {
        PackSpecVersion version = requireActiveVersion();
        List<PackSpecItem> all = itemMapper.selectList(new LambdaQueryWrapper<PackSpecItem>()
                .eq(PackSpecItem::getSpecVersionId, version.getId()));
        List<PackSpecItem> matched = new ArrayList<>();
        for (PackSpecItem item : all) {
            if (item.getMaterial().equals(ctx.relic.getMaterial())
                    && item.getClimateZone().equals(ctx.climateZone)
                    && fitsShape(item, ctx)) {
                matched.add(item);
            }
        }
        if (matched.isEmpty()) {
            throw new IllegalArgumentException("生效规格 " + version.getVersionNo()
                    + " 中无匹配材质【" + ctx.relic.getMaterial() + "】、气候区 " + ctx.climateZone
                    + " 且能容纳实测外形 " + ctx.reqLength + "×" + ctx.reqWidth + "×" + ctx.reqDepth
                    + "mm 的包装条目，外形校验不通过，不能装箱");
        }
        // 外形匹配的条目中，挑出满足本次防震要求者，取防震最严（rank 最小）
        PackSpecItem picked = null;
        for (PackSpecItem item : matched) {
            if (cushionRank(item.getCushionLevel()) <= cushionRank(ctx.requiredCushion)) {
                if (picked == null
                        || cushionRank(item.getCushionLevel()) < cushionRank(picked.getCushionLevel())) {
                    picked = item;
                }
            }
        }
        if (picked == null) {
            throw new IllegalArgumentException("匹配条目的缓冲（防震）等级均低于本次要求 "
                    + ctx.requiredCushion + "，防震校验不通过，不能装箱");
        }
        // 重量硬校验：装箱实测总重不得超过命中条目承重上限
        if (ctx.reqWeight.compareTo(picked.getMaxWeightG()) > 0) {
            throw new IllegalArgumentException("重量校验失败：装箱实测 " + ctx.reqWeight
                    + "g 超过规格条目 " + picked.getSizeCode() + " 承重上限 "
                    + picked.getMaxWeightG() + "g，不能装箱");
        }
        ctx.version = version;
        ctx.item = picked;
        return picked;
    }

    /** 实测长/宽/厚必须同时落在条目适用区间内（装箱外形校验）。 */
    private boolean fitsShape(PackSpecItem item, PackContext ctx) {
        return within(ctx.reqLength, item.getFitLengthMinMm(), item.getFitLengthMaxMm())
                && within(ctx.reqWidth, item.getFitWidthMinMm(), item.getFitWidthMaxMm())
                && within(ctx.reqDepth, item.getFitDepthMinMm(), item.getFitDepthMaxMm());
    }

    private boolean within(BigDecimal v, BigDecimal min, BigDecimal max) {
        return v.compareTo(min) >= 0 && v.compareTo(max) <= 0;
    }

    /** 用给定包装能力做三项硬校验（重量、外形、防震），不通过即拒绝。 */
    private void validateAgainst(PackContext ctx, BigDecimal maxWeight,
                                 BigDecimal lenMin, BigDecimal lenMax,
                                 BigDecimal widMin, BigDecimal widMax,
                                 BigDecimal depMin, BigDecimal depMax,
                                 String cushion, String scope) {
        // 重量校验
        if (ctx.reqWeight.compareTo(maxWeight) > 0) {
            throw new IllegalArgumentException("重量校验失败：装箱实测 " + ctx.reqWeight
                    + "g 超过" + scope + "承重上限 " + maxWeight + "g");
        }
        // 外形校验（长/宽/厚同时在区间内）
        if (out(ctx.reqLength, lenMin, lenMax) || out(ctx.reqWidth, widMin, widMax)
                || out(ctx.reqDepth, depMin, depMax)) {
            throw new IllegalArgumentException("外形校验失败：装箱实测 " + ctx.reqLength + "×"
                    + ctx.reqWidth + "×" + ctx.reqDepth + "mm 超出" + scope + "适用外形区间");
        }
        // 防震校验：包装缓冲等级须不弱于本次防震要求（rank 更小=防震更强）
        if (cushionRank(cushion) > cushionRank(ctx.requiredCushion)) {
            throw new IllegalArgumentException("防震校验失败：" + scope + "缓冲等级 " + cushion
                    + " 低于本次防震要求 " + ctx.requiredCushion);
        }
    }

    private boolean out(BigDecimal v, BigDecimal min, BigDecimal max) {
        return v.compareTo(min) < 0 || v.compareTo(max) > 0;
    }

    /** L1 防震最强 rank=1，L3 最弱 rank=3 */
    private int cushionRank(String level) {
        if (CUSHION_L1.equals(level)) {
            return 1;
        }
        if (CUSHION_L2.equals(level)) {
            return 2;
        }
        return 3;
    }

    /** 临时替代能力校验：重量/外形/缓冲/气候逐项过替代包装实际能力。 */
    private void validateSubstituteCapability(SubstitutePackRequest req, PackContext ctx) {
        if (!req.getSubClimateZone().trim().equals(ctx.climateZone)) {
            throw new IllegalArgumentException("替代包装气候区 " + req.getSubClimateZone()
                    + " 与本次运输气候区 " + ctx.climateZone + " 不一致");
        }
        validateAgainst(ctx, req.getSubMaxWeightG(),
                req.getSubFitLengthMinMm(), req.getSubFitLengthMaxMm(),
                req.getSubFitWidthMinMm(), req.getSubFitWidthMaxMm(),
                req.getSubFitDepthMinMm(), req.getSubFitDepthMaxMm(),
                req.getSubCushionLevel().trim(), "替代包装");
    }

    private PackOrder newOrder(PackCreateRequest req, PackContext ctx, String type, LocalDateTime now) {
        PackOrder order = new PackOrder();
        order.setOrderNo("PK-" + String.format("%06d", orderMapper.nextOrderNo()));
        order.setRequestNo(req.getRequestNo() == null || req.getRequestNo().trim().isEmpty()
                ? null : req.getRequestNo().trim());
        order.setRelicId(ctx.relic.getId());
        order.setAgreementId(ctx.agreement.getId());
        order.setBoxNo(req.getBoxNo().trim());
        order.setPackType(type);
        order.setClimateZone(ctx.climateZone);
        order.setRequiredCushionLevel(ctx.requiredCushion);
        order.setActualWeightG(req.getActualWeightG());
        order.setActualLengthMm(req.getActualLengthMm());
        order.setActualWidthMm(req.getActualWidthMm());
        order.setActualDepthMm(req.getActualDepthMm());
        order.setPackedBy(req.getPackedBy().trim());
        order.setSealedAt(now);
        return order;
    }

    /** 把命中的标准规格条目全量快照进装箱单（规格之后变更不影响本单）。 */
    private void snapshotStandard(PackOrder order, PackSpecItem item) {
        order.setSpecVersionId(item.getSpecVersionId());
        PackSpecVersion version = versionMapper.selectById(item.getSpecVersionId());
        order.setSpecVersionNo(version.getVersionNo());
        order.setSpecItemId(item.getId());
        order.setSpecMaterial(item.getMaterial());
        order.setSpecSizeCode(item.getSizeCode());
        order.setSpecCushionLevel(item.getCushionLevel());
        order.setSpecClimateZone(item.getClimateZone());
        order.setSpecMaxWeightG(item.getMaxWeightG());
        order.setSpecShockLimitG(item.getShockLimitG());
        order.setSpecFitLengthMinMm(item.getFitLengthMinMm());
        order.setSpecFitLengthMaxMm(item.getFitLengthMaxMm());
        order.setSpecFitWidthMinMm(item.getFitWidthMinMm());
        order.setSpecFitWidthMaxMm(item.getFitWidthMaxMm());
        order.setSpecFitDepthMinMm(item.getFitDepthMinMm());
        order.setSpecFitDepthMaxMm(item.getFitDepthMaxMm());
    }

    /**
     * 落库后通知外部依赖。无响应时保持 NOTIFY_PENDING（不假装成功），
     * 返回 notified=false；恢复后由手动/定时补偿按行认领，只通知一次。
     */
    private Map<String, Object> afterPersistNotify(PackOrder order) {
        boolean notified = false;
        String notifyError = null;
        try {
            notified = notifyService.deliverOrder(order.getId());
        } catch (MuseumRepairUnavailableException e) {
            notifyError = e.getMessage();
        }
        PackOrder refreshed = requireOrder(order.getId());
        Map<String, Object> view = orderView(refreshed, notified);
        view.put("notifyError", notifyError);
        return view;
    }

    private void createOutbox(PackOrder order) {
        PackNotifyOutbox outbox = new PackNotifyOutbox();
        outbox.setOrderId(order.getId());
        outbox.setNotifyType("PACK_CONFIRM");
        outbox.setNotifyKey("PACK-" + order.getOrderNo());
        outbox.setStatus(NOTIFY_PENDING);
        outbox.setAttempts(0);
        outboxMapper.insert(outbox);
    }

    /** 生成替代包装相对标准规格的逐项差异文本并固化进审批单。 */
    private void fillDiffs(PackSubstitute sub, PackSpecItem std, SubstitutePackRequest req, PackContext ctx) {
        sub.setCushionDiff(diffCushion(std.getCushionLevel(), req.getSubCushionLevel().trim()));
        sub.setShockDiff(diffNum("防震冲击限值g", std.getShockLimitG(), req.getSubShockLimitG()));
        sub.setWeightDiff(diffNum("承重上限g", std.getMaxWeightG(), req.getSubMaxWeightG()));
        sub.setSizeDiff(diffSize(std, req));
        sub.setClimateDiff(std.getClimateZone().equals(req.getSubClimateZone().trim())
                ? "气候区一致（" + std.getClimateZone() + "）"
                : "气候区 " + std.getClimateZone() + " → " + req.getSubClimateZone().trim());
        List<String> parts = Arrays.asList(
                "缓冲等级：" + sub.getCushionDiff(),
                "防震：" + sub.getShockDiff(),
                "承重：" + sub.getWeightDiff(),
                "外形：" + sub.getSizeDiff(),
                "气候：" + sub.getClimateDiff());
        sub.setDiffDetail(String.join("；", parts));
    }

    private String diffCushion(String stdLevel, String subLevel) {
        if (stdLevel.equals(subLevel)) {
            return "缓冲等级一致（" + stdLevel + "）";
        }
        return "缓冲等级 " + stdLevel + " → " + subLevel
                + (cushionRank(subLevel) > cushionRank(stdLevel) ? "（替代防震弱于标准）" : "（替代防震不弱于标准）");
    }

    private String diffNum(String label, BigDecimal std, BigDecimal sub) {
        int cmp = sub.compareTo(std);
        String arrow = cmp == 0 ? "一致" : (cmp > 0 ? "提高至 " : "降低至 ");
        return label + " 标准 " + std + "，替代 " + sub + (cmp == 0 ? "（一致）" : "（" + arrow + sub + "）");
    }

    private String diffSize(PackSpecItem std, SubstitutePackRequest req) {
        boolean same = std.getFitLengthMinMm().equals(req.getSubFitLengthMinMm())
                && std.getFitLengthMaxMm().equals(req.getSubFitLengthMaxMm())
                && std.getFitWidthMinMm().equals(req.getSubFitWidthMinMm())
                && std.getFitWidthMaxMm().equals(req.getSubFitWidthMaxMm())
                && std.getFitDepthMinMm().equals(req.getSubFitDepthMinMm())
                && std.getFitDepthMaxMm().equals(req.getSubFitDepthMaxMm());
        if (same) {
            return "适用外形区间一致";
        }
        return "适用外形区间由 长[" + std.getFitLengthMinMm() + "~" + std.getFitLengthMaxMm()
                + "] 宽[" + std.getFitWidthMinMm() + "~" + std.getFitWidthMaxMm()
                + "] 厚[" + std.getFitDepthMinMm() + "~" + std.getFitDepthMaxMm()
                + "] 变为 长[" + req.getSubFitLengthMinMm() + "~" + req.getSubFitLengthMaxMm()
                + "] 宽[" + req.getSubFitWidthMinMm() + "~" + req.getSubFitWidthMaxMm()
                + "] 厚[" + req.getSubFitDepthMinMm() + "~" + req.getSubFitDepthMaxMm() + "]";
    }

    private PackOrder findByIdempotentRequest(String requestNo) {
        if (requestNo == null || requestNo.trim().isEmpty()) {
            return null;
        }
        return orderMapper.selectOne(new LambdaQueryWrapper<PackOrder>()
                .eq(PackOrder::getRequestNo, requestNo.trim()).last("LIMIT 1"));
    }

    private long countPhotos(Long orderId, String stage) {
        return photoMapper.selectCount(new LambdaQueryWrapper<PackPhoto>()
                .eq(PackPhoto::getOrderId, orderId)
                .eq(PackPhoto::getPhotoStage, stage));
    }

    private List<PackPhoto> listPhotos(Long orderId, String stage) {
        return photoMapper.selectList(new LambdaQueryWrapper<PackPhoto>()
                .eq(PackPhoto::getOrderId, orderId)
                .eq(PackPhoto::getPhotoStage, stage)
                .orderByAsc(PackPhoto::getId));
    }

    private Map<String, Object> orderView(PackOrder order, Boolean notified) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("order", order);
        PackNotifyOutbox outbox = outboxMapper.selectOne(new LambdaQueryWrapper<PackNotifyOutbox>()
                .eq(PackNotifyOutbox::getOrderId, order.getId()).last("LIMIT 1"));
        view.put("notify", outbox);
        view.put("notified", notified == null
                ? (outbox != null && NOTIFY_SENT.equals(outbox.getStatus())) : notified);
        return view;
    }

    /** 装箱校验上下文：在一次装箱流程内共享文物/押运单/实测值与命中条目。 */
    private static class PackContext {
        Relic relic;
        LoanAgreement agreement;
        String climateZone;
        String requiredCushion;
        RelicIntake intake;
        PackSpecVersion version;
        PackSpecItem item;
        BigDecimal reqWeight;
        BigDecimal reqLength;
        BigDecimal reqWidth;
        BigDecimal reqDepth;
    }
}
