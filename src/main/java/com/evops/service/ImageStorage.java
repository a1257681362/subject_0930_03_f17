package com.evops.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 影像物理存储：SHA-256 内容寻址。
 *
 * <ul>
 *   <li>同一内容只落一份物理文件（{@code ab/cd/&lt;hash&gt;.ext}），重复上传直接复用；</li>
 *   <li>保存前用 ImageIO 解码并读取分辨率，不可解码/尺寸过小在此拦截；</li>
 *   <li>下载按岗位脱敏：非修复师岗位得到加水印、缩放后的副本，原文件不外发。</li>
 * </ul>
 */
@Component
public class ImageStorage {

    private static final Logger log = LoggerFactory.getLogger(ImageStorage.class);

    private final Path root;
    private final int minWidth;
    private final int minHeight;

    private static final Map<String, String> EXT_BY_TYPE = new HashMap<>();

    static {
        EXT_BY_TYPE.put("image/jpeg", "jpg");
        EXT_BY_TYPE.put("image/jpg", "jpg");
        EXT_BY_TYPE.put("image/png", "png");
        EXT_BY_TYPE.put("image/gif", "gif");
        EXT_BY_TYPE.put("image/bmp", "bmp");
    }

    public ImageStorage(@Value("${museum.image.storage-root:./data/images}") String storageRoot,
                        @Value("${museum.image.min-width-px:800}") int minWidth,
                        @Value("${museum.image.min-height-px:800}") int minHeight) {
        this.root = Paths.get(storageRoot).toAbsolutePath().normalize();
        this.minWidth = minWidth;
        this.minHeight = minHeight;
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建影像存储目录: " + this.root, e);
        }
    }

    /** 已解析的照片：内容哈希、分辨率与存储路径 */
    public static class StoredPhoto {
        public final String hash;
        public final String storagePath;
        public final String fileName;
        public final String contentType;
        public final long size;
        public final int width;
        public final int height;

        StoredPhoto(String hash, String storagePath, String fileName, String contentType,
                    long size, int width, int height) {
            this.hash = hash;
            this.storagePath = storagePath;
            this.fileName = fileName;
            this.contentType = contentType;
            this.size = size;
            this.width = width;
            this.height = height;
        }
    }

    public int getMinWidth() {
        return minWidth;
    }

    public int getMinHeight() {
        return minHeight;
    }

    /**
     * 校验分辨率并按内容哈希落盘。文件已存在（同一文件重复上传）时不重复写盘。
     *
     * @throws IllegalArgumentException 内容不可解码为图片，或分辨率低于归档下限
     */
    public StoredPhoto store(byte[] content, String originalName, String contentType) {
        return store(content, originalName, contentType, true);
    }

    /**
     * 按内容哈希落盘。
     *
     * @param enforceMinSize true 时执行归档分辨率下限校验（文物多角度影像）；
     *                       false 时仅要求可解码（展厅异常现场照片，不套归档下限）
     */
    public StoredPhoto store(byte[] content, String originalName, String contentType,
                             boolean enforceMinSize) {
        BufferedImage image = decode(content);
        int width = image.getWidth();
        int height = image.getHeight();
        if (enforceMinSize && (width < minWidth || height < minHeight)) {
            throw new IllegalArgumentException("分辨率 " + width + "x" + height
                    + " 低于归档下限 " + minWidth + "x" + minHeight);
        }

        String hash = sha256(content);
        String ext = resolveExtension(contentType, originalName);
        String relativePath = hash.substring(0, 2) + "/" + hash.substring(2, 4)
                + "/" + hash + "." + ext;
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法存储路径");
        }
        try {
            if (!Files.exists(target)) {
                Files.createDirectories(target.getParent());
                Files.write(target, content);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("影像写入失败", e);
        }
        String safeName = originalName == null || originalName.isEmpty()
                ? hash + "." + ext : Paths.get(originalName).getFileName().toString();
        return new StoredPhoto(hash, relativePath, safeName, contentType,
                content.length, width, height);
    }

    /** 读取原始物理文件（仅完整权限岗位使用） */
    public Path resolveOriginal(String storagePath) {
        Path path = root.resolve(storagePath).normalize();
        if (!path.startsWith(root) || !Files.exists(path)) {
            throw new IllegalArgumentException("影像文件不存在或已不可用");
        }
        return path;
    }

    /**
     * 生成脱敏副本：低权限岗位先缩放再打水印，原文件不出库。
     *
     * @param maxEdge  脱敏后长边最大像素
     * @param markText 水印文字（岗位 + 用途）
     */
    public byte[] desensitize(String storagePath, int maxEdge, String markText) {
        Path path = resolveOriginal(storagePath);
        try {
            BufferedImage src = ImageIO.read(path.toFile());
            if (src == null) {
                throw new IllegalArgumentException("影像无法解码");
            }
            int w = src.getWidth();
            int h = src.getHeight();
            double scale = Math.min(1.0, (double) maxEdge / Math.max(w, h));
            int nw = Math.max(1, (int) Math.round(w * scale));
            int nh = Math.max(1, (int) Math.round(h * scale));

            BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, nw, nh);
            g.drawImage(src, 0, 0, nw, nh, null);

            // 半透明水印条
            g.setColor(new Color(255, 255, 255, 160));
            int band = Math.max(18, nh / 12);
            g.fillRect(0, nh - band, nw, band);
            g.setColor(new Color(180, 0, 0));
            g.setFont(new Font("SansSerif", Font.BOLD, Math.max(12, band / 2)));
            g.drawString(markText, 6, nh - band / 3);
            g.dispose();

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            if (!ImageIO.write(out, "jpg", bos)) {
                throw new IllegalArgumentException("脱敏影像编码失败");
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("脱敏影像生成失败", e);
        }
    }

    private BufferedImage decode(byte[] content) {
        try {
            BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(content));
            if (image == null) {
                throw new IllegalArgumentException("文件不是可识别的影像（支持 JPG/PNG/GIF/BMP）");
            }
            return image;
        } catch (IOException e) {
            throw new IllegalArgumentException("影像解析失败：" + e.getMessage());
        }
    }

    private String resolveExtension(String contentType, String originalName) {
        if (contentType != null) {
            String ext = EXT_BY_TYPE.get(contentType.toLowerCase());
            if (ext != null) {
                return ext;
            }
        }
        if (originalName != null) {
            String lower = originalName.toLowerCase();
            for (String ext : Arrays.asList("jpg", "jpeg", "png", "gif", "bmp")) {
                if (lower.endsWith("." + ext)) {
                    return "jpeg".equals(ext) ? "jpg" : ext;
                }
            }
        }
        return "jpg";
    }

    private String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(content);
            StringBuilder sb = new StringBuilder(hashBytes.length * 2);
            for (byte b : hashBytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
