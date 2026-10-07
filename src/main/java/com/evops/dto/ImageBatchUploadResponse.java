package com.evops.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 影像批量上传结果：按拍摄批次返回。
 * 即使部分照片校验失败，也逐张给出成功/失败与具体原因。
 */
@Data
public class ImageBatchUploadResponse {

    /** 拍摄批次编号 */
    private String batchNo;

    private Long batchId;

    private Long relicId;

    /** 本次批次落定的文物影像版本号（无一张成功时为空） */
    private Integer versionNo;

    private int totalCount;

    private int successCount;

    private int failedCount;

    /** 逐张结果，顺序与上传文件顺序一致 */
    private List<Item> items = new ArrayList<>();

    @Data
    public static class Item {
        /** 该照片在本次提交中的序号（从 0 开始，与 files 索引一致） */
        private int index;

        private String fileName;

        /** FRONT / BACK / BOTTOM / DETAIL */
        private String viewType;

        private boolean success;

        /** 影像版本引用 id（成功时） */
        private Long imageId;

        /** 物理文件 id（成功时） */
        private Long fileId;

        /**
         * 是否复用了已存在的物理文件：同一文件重复上传时为 true，
         * 只保留一份物理文件、新增一条引用。
         */
        private boolean duplicateFile;

        /** 失败具体原因（失败时），如：分辨率不足 / 缺少拍摄时间 / 摄影者为空 */
        private String reason;

        public static Item ok(int index, String fileName, String viewType,
                              Long imageId, Long fileId, boolean duplicateFile) {
            Item item = new Item();
            item.index = index;
            item.fileName = fileName;
            item.viewType = viewType;
            item.success = true;
            item.imageId = imageId;
            item.fileId = fileId;
            item.duplicateFile = duplicateFile;
            return item;
        }

        public static Item fail(int index, String fileName, String viewType, String reason) {
            Item item = new Item();
            item.index = index;
            item.fileName = fileName;
            item.viewType = viewType;
            item.success = false;
            item.reason = reason;
            return item;
        }
    }
}
