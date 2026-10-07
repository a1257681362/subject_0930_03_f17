package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 拍摄批次：一次拍摄的正面/背面/底部/细部照片归一批次保存。
 * 批次可整体作废（status -> VOID），但只置状态，不删除批次、引用与物理文件。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_photo_batch")
public class PhotoBatch extends BaseEntity {

    /** 批次编号，数据库层唯一 */
    private String batchNo;

    private Long relicId;

    /** 关联修复项目（修复阶段拍摄时必填，入藏阶段可为空） */
    private Long projectId;

    /** 关联修复阶段 */
    private Long stageId;

    private String shotLocation;

    private String remark;

    /** 本次提交的照片总数 */
    private Integer totalCount;

    /** 校验失败张数（成功张数 = total - failed） */
    private Integer failedCount;

    /** CONFIRMED 已确认 / VOID 已作废 */
    private String status;

    private String voidReason;

    private LocalDateTime voidedAt;
}
