package com.evops.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 监测明细：每个虫种一行，固化提交时阈值快照与是否超阈值。
 * 历史明细不随后续规则版本改写；规则无条目的虫种 thresholdCount 为 null 不参与判定。
 */
@Data
@TableName("t_pest_monitor_detail")
public class PestMonitorDetail {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long recordId;

    private String pestSpecies;

    private Integer pestCount;

    /** 提交时生效规则版本对该虫种的阈值快照；规则无条目为 null */
    private Integer thresholdCount;

    /** 0 未超阈值 / 1 超阈值 */
    private Integer overThreshold;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
