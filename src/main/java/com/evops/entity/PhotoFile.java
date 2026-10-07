package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 物理影像文件：按 SHA-256 内容寻址，全库唯一一份。
 * 同一文件重复上传只增加 {@link #refCount} 引用，不重复落盘；
 * 影像作废只置引用状态，物理文件永不删除。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_photo_file")
public class PhotoFile extends BaseEntity {

    /** 内容 SHA-256（十六进制），全库唯一 */
    private String contentHash;

    /** 内容寻址存储相对路径，如 ab/cd/&lt;hash&gt;.jpg */
    private String storagePath;

    private String fileName;

    private String contentType;

    private Long fileSize;

    /** 分辨率宽（像素），上传时校验 */
    private Integer widthPx;

    /** 分辨率高（像素），上传时校验 */
    private Integer heightPx;

    /** 拍摄时间，上传时校验 */
    private LocalDateTime shotAt;

    /** 摄影者，上传时校验 */
    private String photographer;

    /** 被影像版本引用的次数 */
    private Integer refCount;
}
