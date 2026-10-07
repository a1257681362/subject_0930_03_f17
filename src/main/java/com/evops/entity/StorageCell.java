package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 格位：密集架上的具体存放格口，按温区/防火等级/承重维护。
 * sensitive=1 为敏感位置，库存查询对低权限馆员脱敏/过滤。
 * status: ACTIVE 可用 / DISABLED 已停用。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_storage_cell")
public class StorageCell extends BaseEntity {

    private String cellNo;

    private Long areaId;

    private Long rackId;

    private Integer shelfNo;

    private Integer colNo;

    private String tempZone;

    private String fireRating;

    private BigDecimal loadLimitKg;

    /** 0 普通 / 1 敏感位置 */
    private Integer sensitive;

    /** ACTIVE / DISABLED */
    private String status;
}
