package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 虫害规则条目：每个虫种一行（同版本内虫种唯一），固化阈值数量。
 * 监测记录中该虫种数量 &gt; thresholdCount 即判定超阈值。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_pest_rule_item")
public class PestRuleItem extends BaseEntity {

    private Long ruleVersionId;

    /** 虫种（如 衣鱼 / 书虱 / 烟草甲） */
    private String pestSpecies;

    /** 阈值数量：监测数量严格大于该值判定超阈值 */
    private Integer thresholdCount;
}
