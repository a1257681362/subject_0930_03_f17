package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 库区：按温区、防火等级、承重维护。
 * tempZone: CONST_TEMP 恒温 / COLD 冷藏 / ORDINARY 常温；
 * fireRating: F1 / F2 / F3；status: ACTIVE / CLOSED。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_storage_area")
public class StorageArea extends BaseEntity {

    private String areaNo;

    private String name;

    private String tempZone;

    private String fireRating;

    private BigDecimal loadCapacityKg;

    private String location;

    private String manager;

    /** ACTIVE / CLOSED */
    private String status;
}
