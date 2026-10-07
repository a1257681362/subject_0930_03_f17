package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.constant.MuseumConstants;
import com.evops.dto.ImageBatchUploadResponse;
import com.evops.entity.PhotoBatch;
import com.evops.entity.PhotoFile;
import com.evops.entity.RelicImage;
import com.evops.entity.RepairProject;
import com.evops.entity.RepairStage;
import com.evops.mapper.PhotoBatchMapper;
import com.evops.mapper.PhotoFileMapper;
import com.evops.mapper.RelicImageMapper;
import com.evops.mapper.RepairProjectMapper;
import com.evops.mapper.RepairStageMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.evops.constant.MuseumConstants.*;

/**
 * 文物多角度影像归档。
 *
 * <p>规则汇总：</p>
 * <ol>
 *   <li>正面/背面/底部/细部照片按<b>拍摄批次</b>保存，逐张校验视角、分辨率、拍摄时间、摄影者，
 *       批量上传失败时逐张返回具体原因（{@link ImageBatchUploadResponse.Item}）。</li>
 *   <li>物理文件按 SHA-256 内容寻址，同一文件重复上传只新增引用、只保留一份物理文件。</li>
 *   <li>不同修复阶段必须形成新版本：同一阶段补拍沿用当前版本号，换阶段（或非修复场景）
 *       版本号递增；版本只追加、不覆盖。</li>
 *   <li>作废只把影像/批次置 VOID，不删除原文件与引用。</li>
 *   <li>下载按岗位脱敏；权限收紧时上传在入口处停止，已确认版本全部保留。</li>
 * </ol>
 */
@Service
public class ImageArchiveService {

    private static final List<String> VIEW_TYPES =
            Arrays.asList(VIEW_FRONT, VIEW_BACK, VIEW_BOTTOM, VIEW_DETAIL);

    private final PhotoFileMapper fileMapper;
    private final PhotoBatchMapper batchMapper;
    private final RelicImageMapper imageMapper;
    private final RepairProjectMapper projectMapper;
    private final RepairStageMapper stageMapper;
    private final ImageStorage storage;
    private final PermissionService permissionService;
    private final RelicService relicService;
    private final LedgerService ledgerService;

    public ImageArchiveService(PhotoFileMapper fileMapper,
                               PhotoBatchMapper batchMapper,
                               RelicImageMapper imageMapper,
                               RepairProjectMapper projectMapper,
                               RepairStageMapper stageMapper,
                               ImageStorage storage,
                               PermissionService permissionService,
                               RelicService relicService,
                               LedgerService ledgerService) {
        this.fileMapper = fileMapper;
        this.batchMapper = batchMapper;
        this.imageMapper = imageMapper;
        this.projectMapper = projectMapper;
        this.stageMapper = stageMapper;
        this.storage = storage;
        this.permissionService = permissionService;
        this.relicService = relicService;
        this.ledgerService = ledgerService;
    }

    /** 待归档照片（控制器完成 multipart 解析后传入） */
    public static class PendingPhoto {
        public byte[] content;
        public String fileName;
        public String contentType;
        /** FRONT / BACK / BOTTOM / DETAIL */
        public String viewType;
        /** 拍摄时间（为空将以“缺少拍摄时间”拒收） */
        public LocalDateTime shotAt;
        /** 拍摄时间原始字符串：非空但解析失败时逐张给出格式原因 */
        public String shotAtRaw;
        /** 摄影者（为空将以“缺少摄影者”拒收） */
        public String photographer;

        public PendingPhoto() {
        }

        public PendingPhoto(byte[] content, String fileName, String contentType,
                            String viewType, LocalDateTime shotAt, String photographer) {
            this.content = content;
            this.fileName = fileName;
            this.contentType = contentType;
            this.viewType = viewType;
            this.shotAt = shotAt;
            this.photographer = photographer;
        }
    }

    /**
     * 批量上传一个拍摄批次。权限收紧时在入口处直接拒绝、不推进任何写入。
     * 批次内逐张校验：失败张不入库，成功张落版本引用；结果逐张返回。
     */
    @Transactional
    public ImageBatchUploadResponse uploadBatch(Long relicId, Long projectId, Long stageId,
                                                String shotLocation, String remark,
                                                List<PendingPhoto> photos) {
        // 权限闸门：收紧后上传立即停止推进
        permissionService.requireUploadOpen();

        // 文物必须在档（未撤销）才能归档影像；无效/已撤销在此抛异常
        relicService.requireActiveRelic(relicId);
        if (photos == null || photos.isEmpty()) {
            throw new IllegalArgumentException("拍摄批次至少包含一张照片");
        }
        validateStageContext(relicId, projectId, stageId);

        ImageBatchUploadResponse resp = new ImageBatchUploadResponse();
        resp.setRelicId(relicId);
        resp.setTotalCount(photos.size());

        int index = 0;
        List<AcceptedPhoto> accepted = new ArrayList<>();
        for (PendingPhoto photo : photos) {
            String viewType = photo.viewType == null ? null : photo.viewType.trim().toUpperCase();
            String fileName = photo.fileName;
            try {
                String validationError = validateItem(photo, viewType);
                if (validationError != null) {
                    resp.getItems().add(ImageBatchUploadResponse.Item.fail(
                            index, fileName, viewType, validationError));
                    index++;
                    continue;
                }
                // 分辨率校验 + 内容寻址落盘（同一内容不重复写盘）
                ImageStorage.StoredPhoto stored;
                try {
                    stored = storage.store(photo.content, fileName, photo.contentType);
                } catch (IllegalArgumentException | UncheckedIOException ex) {
                    resp.getItems().add(ImageBatchUploadResponse.Item.fail(
                            index, fileName, viewType, ex.getMessage()));
                    index++;
                    continue;
                }
                PhotoFile file;
                boolean duplicate;
                try {
                    ExistingFile found = obtainPhysicalFile(stored, photo);
                    file = found.file;
                    duplicate = found.duplicate;
                } catch (Exception ex) {
                    resp.getItems().add(ImageBatchUploadResponse.Item.fail(
                            index, fileName, viewType,
                            ex.getMessage() == null ? "物理文件登记失败" : ex.getMessage()));
                    index++;
                    continue;
                }
                accepted.add(new AcceptedPhoto(file, viewType, photo, index));
                resp.getItems().add(ImageBatchUploadResponse.Item.ok(
                        index, fileName, viewType, null, file.getId(), duplicate));
            } catch (Exception ex) {
                // 单张异常不拖垮整批，原因精确返回给该张
                resp.getItems().add(ImageBatchUploadResponse.Item.fail(
                        index, fileName, viewType,
                        ex.getMessage() == null ? "处理失败" : ex.getMessage()));
            }
            index++;
        }

        int successCount = accepted.size();
        int failedCount = photos.size() - successCount;
        resp.setSuccessCount(successCount);
        resp.setFailedCount(failedCount);

        // 全部失败：不产生空批次/空版本，但每张失败原因照常返回
        if (successCount == 0) {
            return resp;
        }

        Integer versionNo = resolveVersionNo(relicId, stageId);
        resp.setVersionNo(versionNo);

        PhotoBatch batch = new PhotoBatch();
        batch.setBatchNo(generateBatchNo());
        batch.setRelicId(relicId);
        batch.setProjectId(projectId);
        batch.setStageId(stageId);
        batch.setShotLocation(shotLocation);
        batch.setRemark(remark);
        batch.setTotalCount(photos.size());
        batch.setFailedCount(failedCount);
        batch.setStatus(BATCH_CONFIRMED);
        batchMapper.insert(batch);
        resp.setBatchId(batch.getId());
        resp.setBatchNo(batch.getBatchNo());

        LocalDateTime now = LocalDateTime.now();
        for (AcceptedPhoto ap : accepted) {
            RelicImage image = new RelicImage();
            image.setRelicId(relicId);
            image.setBatchId(batch.getId());
            image.setFileId(ap.file.getId());
            image.setProjectId(projectId);
            image.setStageId(stageId);
            image.setVersionNo(versionNo);
            image.setViewType(ap.viewType);
            image.setPhotographer(ap.photo.photographer.trim());
            image.setShotAt(ap.photo.shotAt);
            image.setWidthPx(ap.file.getWidthPx());
            image.setHeightPx(ap.file.getHeightPx());
            image.setStatus(IMAGE_ACTIVE);
            imageMapper.insert(image);
            // 用该张在本次提交中的序号精确回填引用 id
            resp.getItems().get(ap.index).setImageId(image.getId());
        }

        ledgerService.append(relicId, projectId, null,
                ACTION_IMAGE_UPLOAD, batch.getBatchNo(),
                        "影像批次归档，版本 V" + versionNo
                        + "，成功 " + successCount + " 张、失败 " + failedCount + " 张"
                        + (failedCount > 0 ? "，失败原因：" + summarizeFailures(resp.getItems()) : ""),
                now);
        return resp;
    }

    /** 作废单张影像：只置 VOID 并留痕，原文件保留 */
    @Transactional
    public RelicImage voidImage(Long imageId, String reason, String operator) {
        RelicImage image = imageMapper.selectById(imageId);
        if (image == null) {
            throw new IllegalArgumentException("影像不存在: id=" + imageId);
        }
        if (IMAGE_VOID.equals(image.getStatus())) {
            throw new IllegalArgumentException("影像已作废，不能重复作废");
        }
        LocalDateTime now = LocalDateTime.now();
        image.setStatus(IMAGE_VOID);
        image.setVoidReason(reason);
        image.setVoidedAt(now);
        imageMapper.updateById(image);

        ledgerService.append(image.getRelicId(), image.getProjectId(), null,
                ACTION_IMAGE_VOID, "IMAGE#" + imageId,
                "影像作废（版本 V" + image.getVersionNo() + " " + viewLabel(image.getViewType())
                        + "），原文件保留。原因：" + reason
                        + (operator != null && !operator.isEmpty() ? "，操作人：" + operator : ""),
                now);
        return image;
    }

    /** 作废整个拍摄批次：批次与其中仍有效影像一并置 VOID，原文件全部保留 */
    @Transactional
    public PhotoBatch voidBatch(Long batchId, String reason, String operator) {
        PhotoBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new IllegalArgumentException("拍摄批次不存在: id=" + batchId);
        }
        if (IMAGE_VOID.equals(batch.getStatus())) {
            throw new IllegalArgumentException("批次已作废，不能重复作废");
        }
        LocalDateTime now = LocalDateTime.now();
        List<RelicImage> images = imageMapper.selectList(new LambdaQueryWrapper<RelicImage>()
                .eq(RelicImage::getBatchId, batchId));
        int affected = 0;
        for (RelicImage image : images) {
            if (IMAGE_ACTIVE.equals(image.getStatus())) {
                image.setStatus(IMAGE_VOID);
                image.setVoidReason(reason);
                image.setVoidedAt(now);
                imageMapper.updateById(image);
                affected++;
            }
        }
        batch.setStatus(IMAGE_VOID);
        batch.setVoidReason(reason);
        batch.setVoidedAt(now);
        batchMapper.updateById(batch);

        ledgerService.append(batch.getRelicId(), batch.getProjectId(), null,
                ACTION_IMAGE_VOID, batch.getBatchNo(),
                "拍摄批次整批作废，涉及有效影像 " + affected + " 张，原文件全部保留。原因：" + reason
                        + (operator != null && !operator.isEmpty() ? "，操作人：" + operator : ""),
                now);
        return batch;
    }

    /**
     * 影像版本清单（按版本分组）。非修复师岗位对摄影者姓名做脱敏。
     */
    public List<Map<String, Object>> listVersions(Long relicId, String role) {
        relicService.requireRelic(relicId);
        List<RelicImage> images = imageMapper.selectList(new LambdaQueryWrapper<RelicImage>()
                .eq(RelicImage::getRelicId, relicId)
                .orderByAsc(RelicImage::getVersionNo)
                .orderByAsc(RelicImage::getId));
        boolean mask = !ROLE_RESTORER.equals(normalizeRole(role));

        Map<Integer, Map<String, Object>> groups = new LinkedHashMap<>();
        for (RelicImage image : images) {
            Map<String, Object> group = groups.computeIfAbsent(image.getVersionNo(), v -> {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("versionNo", v);
                g.put("projectId", image.getProjectId());
                g.put("stageId", image.getStageId());
                g.put("images", new ArrayList<Map<String, Object>>());
                return g;
            });
            group.put("projectId", image.getProjectId());
            group.put("stageId", image.getStageId());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> list = (List<Map<String, Object>>) group.get("images");
            list.add(toImageView(image, mask));
        }
        return new ArrayList<>(groups.values());
    }

    /** 批次详情：批次头 + 逐张影像（含物理文件元数据） */
    public Map<String, Object> getBatch(Long batchId, String role) {
        PhotoBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new IllegalArgumentException("拍摄批次不存在: id=" + batchId);
        }
        boolean mask = !ROLE_RESTORER.equals(normalizeRole(role));
        List<RelicImage> images = imageMapper.selectList(new LambdaQueryWrapper<RelicImage>()
                .eq(RelicImage::getBatchId, batchId)
                .orderByAsc(RelicImage::getId));
        List<Map<String, Object>> views = new ArrayList<>();
        for (RelicImage image : images) {
            views.add(toImageView(image, mask));
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("batch", batch);
        detail.put("images", views);
        return detail;
    }

    /** 取影像 + 物理文件；已作废影像不提供下载（记录仍保留、可查不可取原文件） */
    public DownloadBundle resolveForDownload(Long imageId, String role) {
        RelicImage image = imageMapper.selectById(imageId);
        if (image == null) {
            throw new IllegalArgumentException("影像不存在: id=" + imageId);
        }
        if (IMAGE_VOID.equals(image.getStatus())) {
            throw new IllegalArgumentException("影像已作废，不能下载（原文件仍保留留档）");
        }
        PhotoFile file = fileMapper.selectById(image.getFileId());
        if (file == null) {
            throw new IllegalArgumentException("影像物理文件缺失");
        }
        String normalizedRole = normalizeRole(role);
        String downloadName = file.getFileName();

        if (ROLE_RESTORER.equals(normalizedRole)) {
            // 修复师：原件
            java.nio.file.Path path = storage.resolveOriginal(file.getStoragePath());
            try {
                return new DownloadBundle(java.nio.file.Files.readAllBytes(path),
                        file.getContentType(), downloadName, false);
            } catch (java.io.IOException e) {
                throw new UncheckedIOException("原件读取失败", e);
            }
        }
        // 其余岗位：脱敏副本（缩放 + 水印），原文件不出库
        int maxEdge = ROLE_CURATOR.equals(normalizedRole) ? 1280 : 640;
        String mark = (ROLE_CURATOR.equals(normalizedRole) ? "馆藏脱敏·CURATOR" : "观摩用·VIEWER")
                + "·" + image.getRelicId();
        byte[] bytes = storage.desensitize(file.getStoragePath(), maxEdge, mark);
        String baseName = downloadName;
        int dot = baseName.lastIndexOf('.');
        if (dot > 0) {
            baseName = baseName.substring(0, dot);
        }
        return new DownloadBundle(bytes, "image/jpeg",
                baseName + "-desensitized-" + normalizedRole.toLowerCase() + ".jpg", true);
    }

    /** 下载内容包 */
    public static class DownloadBundle {
        public final byte[] content;
        public final String contentType;
        public final String fileName;
        public final boolean desensitized;

        DownloadBundle(byte[] content, String contentType, String fileName, boolean desensitized) {
            this.content = content;
            this.contentType = contentType;
            this.fileName = fileName;
            this.desensitized = desensitized;
        }
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    private String validateItem(PendingPhoto photo, String viewType) {
        if (viewType == null || !VIEW_TYPES.contains(viewType)) {
            return "视角非法，只能为 FRONT/BACK/BOTTOM/DETAIL（正面/背面/底部/细部）";
        }
        if (photo.content == null || photo.content.length == 0) {
            return "照片内容为空";
        }
        if (photo.shotAt == null) {
            return photo.shotAtRaw != null && !photo.shotAtRaw.trim().isEmpty()
                    ? "拍摄时间格式错误，应为 yyyy-MM-ddTHH:mm:ss：" + photo.shotAtRaw
                    : "缺少拍摄时间，不能归档";
        }
        if (photo.photographer == null || photo.photographer.trim().isEmpty()) {
            return "缺少摄影者，不能归档";
        }
        if (photo.shotAt.isAfter(LocalDateTime.now().plusMinutes(1))) {
            return "拍摄时间不能晚于当前时间";
        }
        return null;
    }

    private void validateStageContext(Long relicId, Long projectId, Long stageId) {
        if (projectId == null) {
            if (stageId != null) {
                throw new IllegalArgumentException("指定修复阶段时必须同时指定修复项目");
            }
            return;
        }
        RepairProject project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new IllegalArgumentException("修复项目不存在: id=" + projectId);
        }
        if (!relicId.equals(project.getRelicId())) {
            throw new IllegalArgumentException("修复项目不属于该文物，影像不能跨文物归档");
        }
        if (stageId != null) {
            RepairStage stage = stageMapper.selectById(stageId);
            if (stage == null) {
                throw new IllegalArgumentException("修复阶段不存在: id=" + stageId);
            }
            if (!projectId.equals(stage.getProjectId())) {
                throw new IllegalArgumentException("修复阶段不属于该修复项目");
            }
        }
    }

    /**
     * 版本号裁决：与最近一次归档同属一个修复阶段则沿用该版本（同阶段补拍不另起版本）；
     * 阶段不同（含非修复场景）必须新开版本，版本号文物内递增。
     */
    private Integer resolveVersionNo(Long relicId, Long stageId) {
        RelicImage latest = imageMapper.selectList(new LambdaQueryWrapper<RelicImage>()
                        .eq(RelicImage::getRelicId, relicId)
                        .orderByDesc(RelicImage::getVersionNo)
                        .orderByDesc(RelicImage::getId)
                        .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
        if (latest == null) {
            return 1;
        }
        if (stageId != null && stageId.equals(latest.getStageId())) {
            return latest.getVersionNo();
        }
        return latest.getVersionNo() + 1;
    }

    /**
     * 按内容哈希取物理文件：存在则复用并增加引用计数（同一文件只保留一份物理文件）；
     * 不存在则登记新文件。并发首传撞唯一键时回查复用。
     */
    private ExistingFile obtainPhysicalFile(ImageStorage.StoredPhoto stored, PendingPhoto photo) {
        PhotoFile existing = fileMapper.selectOne(new LambdaQueryWrapper<PhotoFile>()
                .eq(PhotoFile::getContentHash, stored.hash));
        if (existing != null) {
            existing.setRefCount(existing.getRefCount() + 1);
            fileMapper.updateById(existing);
            return new ExistingFile(existing, true);
        }
        PhotoFile file = new PhotoFile();
        file.setContentHash(stored.hash);
        file.setStoragePath(stored.storagePath);
        file.setFileName(stored.fileName);
        file.setContentType(stored.contentType);
        file.setFileSize(stored.size);
        file.setWidthPx(stored.width);
        file.setHeightPx(stored.height);
        // 物理文件行记录首次归档申报的拍摄元数据；重复上传复用此行
        file.setShotAt(photo.shotAt);
        file.setPhotographer(photo.photographer.trim());
        file.setRefCount(1);
        try {
            fileMapper.insert(file);
            return new ExistingFile(file, false);
        } catch (DuplicateKeyException dup) {
            PhotoFile raced = fileMapper.selectOne(new LambdaQueryWrapper<PhotoFile>()
                    .eq(PhotoFile::getContentHash, stored.hash));
            raced.setRefCount(raced.getRefCount() + 1);
            fileMapper.updateById(raced);
            return new ExistingFile(raced, true);
        }
    }

    /** 物理文件解析结果：文件 + 是否复用了既有物理文件 */
    private static class ExistingFile {
        final PhotoFile file;
        final boolean duplicate;

        ExistingFile(PhotoFile file, boolean duplicate) {
            this.file = file;
            this.duplicate = duplicate;
        }
    }

    private String generateBatchNo() {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        int rnd = java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000);
        return "PB" + ts + "-" + String.format("%06d", rnd);
    }

    private String summarizeFailures(List<ImageBatchUploadResponse.Item> items) {
        StringBuilder sb = new StringBuilder();
        for (ImageBatchUploadResponse.Item item : items) {
            if (!item.isSuccess()) {
                if (sb.length() > 0) {
                    sb.append("；");
                }
                sb.append("第").append(item.getIndex() + 1).append("张(")
                        .append(item.getFileName() == null ? "?" : item.getFileName())
                        .append(")").append(item.getReason());
                if (sb.length() > 400) {
                    sb.append("…");
                    break;
                }
            }
        }
        return sb.toString();
    }

    private Map<String, Object> toImageView(RelicImage image, boolean maskPhotographer) {
        PhotoFile file = fileMapper.selectById(image.getFileId());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("imageId", image.getId());
        view.put("batchId", image.getBatchId());
        view.put("versionNo", image.getVersionNo());
        view.put("viewType", image.getViewType());
        view.put("viewLabel", viewLabel(image.getViewType()));
        view.put("status", image.getStatus());
        view.put("shotAt", image.getShotAt());
        view.put("photographer",
                maskPhotographer ? maskName(image.getPhotographer()) : image.getPhotographer());
        view.put("widthPx", image.getWidthPx());
        view.put("heightPx", image.getHeightPx());
        if (file != null) {
            view.put("fileName", file.getFileName());
            view.put("fileSize", file.getFileSize());
            view.put("contentHashPrefix",
                    file.getContentHash() == null ? null : file.getContentHash().substring(0, 12));
        }
        return view;
    }

    private String normalizeRole(String role) {
        if (role == null || role.trim().isEmpty()) {
            // 未声明岗位按最低权限（观摩）处理
            return MuseumConstants.ROLE_VIEWER;
        }
        String r = role.trim().toUpperCase();
        if (!ROLE_RESTORER.equals(r) && !ROLE_CURATOR.equals(r) && !ROLE_VIEWER.equals(r)) {
            throw new IllegalArgumentException("未知岗位: " + role
                    + "，仅支持 RESTORER/CURATOR/VIEWER");
        }
        return r;
    }

    private String maskName(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        String trimmed = name.trim();
        if (trimmed.length() <= 1) {
            return trimmed + "**";
        }
        return trimmed.substring(0, 1) + "**";
    }

    private String viewLabel(String viewType) {
        if (viewType == null) {
            return "未知";
        }
        switch (viewType) {
            case VIEW_FRONT: return "正面";
            case VIEW_BACK: return "背面";
            case VIEW_BOTTOM: return "底部";
            case VIEW_DETAIL: return "细部";
            default: return viewType;
        }
    }

    /** 暂存已通过校验、待落引用的照片 */
    private static class AcceptedPhoto {
        final PhotoFile file;
        final String viewType;
        final PendingPhoto photo;
        /** 该张在本次提交中的序号（成功项在 resp.items 中的索引） */
        final int index;

        AcceptedPhoto(PhotoFile file, String viewType, PendingPhoto photo, int index) {
            this.file = file;
            this.viewType = viewType;
            this.photo = photo;
            this.index = index;
        }
    }
}
