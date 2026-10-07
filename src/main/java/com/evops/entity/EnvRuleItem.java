package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 规则条目：同一版本内每个材质一行，固化温湿度/照度阈值与采样频率（分钟）。
 * 按展厅生成任务时，节拍取展厅内在展文物各材质频率的最小值。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_env_rule_item")
public class EnvRuleItem extends BaseEntity {

    private Long ruleVersionId;

    private String material;

    /** 温度下限/上限（℃） */
    private BigDecimal tempMin;
    private BigDecimal tempMax;

    /** 相对湿度下限/上限（%） */
    private BigDecimal humidityMin;
    private BigDecimal humidityMax;

    /** 照度下限/上限（lux） */
    private BigDecimal luxMin;
    private BigDecimal luxMax;

    /** 采样频率（分钟） */
    private Integer sampleIntervalMin;
}
