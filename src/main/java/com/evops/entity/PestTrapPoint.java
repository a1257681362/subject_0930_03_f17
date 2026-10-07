package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 诱捕点位：展厅（HALL）/库房（STORAGE）布放。
 * 编号全库唯一，登记布放日期、当前坐标与责任区域；
 * 点位迁移不原地改历史——前后坐标留痕见 {@link PestPointMove}，本行只保存当前坐标。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_pest_trap_point")
public class PestTrapPoint extends BaseEntity {

    private String pointNo;

    /** HALL 展厅 / STORAGE 库房 */
    private String zoneType;

    /** 展厅引用 t_hall.id，库房引用 t_storage_area.id */
    private Long zoneId;

    /** 区域名称快照 */
    private String zoneName;

    /** 责任区域（处置任务关联） */
    private String responsibleArea;

    /** 当前坐标（迁移历史见 t_pest_point_move） */
    private BigDecimal coordX;
    private BigDecimal coordY;

    /** 布放日期 */
    private LocalDateTime deployedAt;

    private String deployedBy;

    /** ACTIVE 在用 / RETIRED 停用（停用不删历史） */
    private String status;
}
