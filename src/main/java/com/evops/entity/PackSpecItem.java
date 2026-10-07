package com.evops.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 包装规格条目：材质 + 尺寸档 + 缓冲等级 + 气候区 唯一定位一条。
 * 固化适用外形区间、承重上限与防震（允许冲击加速度 g）限值；
 * 装箱时把整条快照复制进装箱单，规格新版本不改写历史装箱事实。
 */
@Data
@TableName("t_pack_spec_item")
public class PackSpecItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long specVersionId;
    private String material;
    private String sizeCode;

    private BigDecimal fitLengthMinMm;
    private BigDecimal fitLengthMaxMm;
    private BigDecimal fitWidthMinMm;
    private BigDecimal fitWidthMaxMm;
    private BigDecimal fitDepthMinMm;
    private BigDecimal fitDepthMaxMm;
    private BigDecimal maxWeightG;

    /** 缓冲（防震）等级 L1/L2/L3 */
    private String cushionLevel;
    /** 允许冲击加速度上限（g） */
    private BigDecimal shockLimitG;
    /** 气候区 TEMPERATE/HOT_HUMID/COLD/DRY */
    private String climateZone;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
