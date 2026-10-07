package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 文物影像版本引用：一张照片一行，归属拍摄批次与版本。
 *
 * <p>同一物理文件（{@link #fileId}）可被多个视角/批次引用，只保留一份物理文件；
 * 不同修复阶段的影像必须形成新版本（{@link #versionNo} 文物内递增，不可覆盖）；
 * 作废只把 {@link #status} 置为 VOID，不删行、不删物理文件。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_relic_image")
public class RelicImage extends BaseEntity {

    private Long relicId;

    private Long batchId;

    /** 指向物理文件 t_photo_file.id（去重后的唯一一份） */
    private Long fileId;

    private Long projectId;

    private Long stageId;

    /** 文物内版本号，从 1 递增；不同修复阶段必须递增 */
    private Integer versionNo;

    /** FRONT 正面 / BACK 背面 / BOTTOM 底部 / DETAIL 细部 */
    private String viewType;

    private String photographer;

    private LocalDateTime shotAt;

    private Integer widthPx;

    private Integer heightPx;

    /** ACTIVE 有效 / VOID 已作废 */
    private String status;

    private String voidReason;

    private LocalDateTime voidedAt;
}
