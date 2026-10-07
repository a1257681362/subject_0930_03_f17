package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.ImageBatchUploadResponse;
import com.evops.dto.VoidReasonRequest;
import com.evops.entity.PhotoBatch;
import com.evops.entity.RelicImage;
import com.evops.service.ImageArchiveService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.Valid;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 文物多角度影像归档接口。
 *
 * <p>上传使用 multipart/form-data：{@code files} 为照片，与之同序的
 * {@code viewTypes / shotAts / photographers} 分别给出每张的视角、拍摄时间与摄影者。
 * 逐张校验、逐张返回失败原因；同文件去重、跨修复阶段新版本、作废不删文件、
 * 下载按岗位脱敏、权限收紧时上传停止，均在 {@link ImageArchiveService} 落实。</p>
 */
@RestController
@RequestMapping("/api")
public class ImageArchiveController {

    private static final DateTimeFormatter SPACE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ImageArchiveService imageService;

    public ImageArchiveController(ImageArchiveService imageService) {
        this.imageService = imageService;
    }

    /**
     * 批量上传一个拍摄批次（正面/背面/底部/细部）。
     * 权限收紧时整体拒绝；批次内失败张逐张给出原因，不影响其余张归档。
     */
    @PostMapping(value = "/relics/{relicId}/image-batches",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ImageBatchUploadResponse> upload(
            @PathVariable Long relicId,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long stageId,
            @RequestParam(required = false) String shotLocation,
            @RequestParam(required = false) String remark,
            @RequestParam(value = "files", required = false) List<MultipartFile> files,
            @RequestParam(value = "viewTypes", required = false) List<String> viewTypes,
            @RequestParam(value = "shotAts", required = false) List<String> shotAts,
            @RequestParam(value = "photographers", required = false) List<String> photographers)
            throws IOException {

        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("拍摄批次至少上传一张照片");
        }
        List<ImageArchiveService.PendingPhoto> photos = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            MultipartFile mf = files.get(i);
            ImageArchiveService.PendingPhoto photo = new ImageArchiveService.PendingPhoto();
            photo.content = mf.isEmpty() ? new byte[0] : mf.getBytes();
            photo.fileName = mf.getOriginalFilename();
            photo.contentType = mf.getContentType();
            photo.viewType = at(viewTypes, i);
            String shotRaw = at(shotAts, i);
            photo.shotAtRaw = shotRaw;
            photo.shotAt = parseShotAt(shotRaw);
            photo.photographer = at(photographers, i);
            photos.add(photo);
        }
        return ApiResponse.ok(imageService.uploadBatch(
                relicId, projectId, stageId, shotLocation, remark, photos));
    }

    /** 影像版本清单（按版本分组，不同修复阶段为不同版本） */
    @GetMapping("/relics/{relicId}/image-versions")
    public ApiResponse<List<Map<String, Object>>> versions(
            @PathVariable Long relicId,
            @RequestParam(value = "role", required = false) String role) {
        return ApiResponse.ok(imageService.listVersions(relicId, role));
    }

    /** 拍摄批次详情 */
    @GetMapping("/image-batches/{batchId}")
    public ApiResponse<Map<String, Object>> batch(
            @PathVariable Long batchId,
            @RequestParam(value = "role", required = false) String role) {
        return ApiResponse.ok(imageService.getBatch(batchId, role));
    }

    /** 作废单张影像：原文件保留 */
    @PostMapping("/images/{imageId}/void")
    public ApiResponse<RelicImage> voidImage(@PathVariable Long imageId,
                                             @Valid @RequestBody VoidReasonRequest request) {
        return ApiResponse.ok(imageService.voidImage(
                imageId, request.getReason(), request.getOperator()));
    }

    /** 作废整个拍摄批次 */
    @PostMapping("/image-batches/{batchId}/void")
    public ApiResponse<PhotoBatch> voidBatch(@PathVariable Long batchId,
                                             @Valid @RequestBody VoidReasonRequest request) {
        return ApiResponse.ok(imageService.voidBatch(
                batchId, request.getReason(), request.getOperator()));
    }

    /**
     * 按岗位下载：修复师取原件；馆藏保管员/观摩访客取缩放水印脱敏副本。
     */
    @GetMapping("/images/{imageId}/download")
    public ResponseEntity<byte[]> download(
            @PathVariable Long imageId,
            @RequestParam(value = "role", required = false) String role)
            throws java.io.UnsupportedEncodingException {
        ImageArchiveService.DownloadBundle bundle = imageService.resolveForDownload(imageId, role);
        String encodedName = URLEncoder.encode(bundle.fileName, StandardCharsets.UTF_8.name())
                .replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + encodedName + "\"; filename*=UTF-8''" + encodedName)
                .contentType(MediaType.parseMediaType(bundle.contentType))
                .body(bundle.content);
    }

    // ------------------------------------------------------------------

    private String at(List<String> values, int index) {
        return values != null && index < values.size() ? values.get(index) : null;
    }

    private LocalDateTime parseShotAt(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String v = raw.trim();
        try {
            return LocalDateTime.parse(v);
        } catch (Exception ignore) {
            // 允许 "yyyy-MM-dd HH:mm:ss"
            try {
                return LocalDateTime.parse(v, SPACE_FORMAT);
            } catch (Exception e) {
                return null;
            }
        }
    }
}
