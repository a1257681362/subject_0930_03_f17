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
 * 读数按材质判定明细：点位展陈的每种在规则内材质一行，
 * 固化该材质当时阈值与越限指标——不同材质引用不同阈值、历史不可改写。
 */
@Data
@TableName("t_env_reading_detail")
public class EnvReadingDetail {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long readingId;

    private String material;

    private BigDecimal tempMin;
    private BigDecimal tempMax;
    private BigDecimal humidityMin;
    private BigDecimal humidityMax;
    private BigDecimal luxMin;
    private BigDecimal luxMax;

    /** 该材质口径下越限指标码逗号拼接（TEMP/HUMIDITY/LUX），正常为 null */
    private String abnormalMetrics;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
