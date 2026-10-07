package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 工单材料领用单：修复工单一次领料一张单，含至少一条明细。
 * status: ISSUED 已领用 / RETURNED 整单退齐；
 * varianceFlag=1 表示存在超阈值差异行，reviewStatus 跟踪整单复核进度。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_material_usage")
public class MaterialUsage extends BaseEntity {

    /** 领用单号，全局唯一 */
    private String usageNo;

    private Long projectId;

    private Long relicId;

    private LocalDateTime usageAt;

    /** ISSUED / RETURNED */
    private String status;

    /** 0 无超阈值差异 / 1 有超阈值差异 */
    private Integer varianceFlag;

    /** NONE / PENDING / CONFIRMED */
    private String reviewStatus;

    private String note;
}
