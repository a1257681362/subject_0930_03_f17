package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 库位预约（兼移动主单）：搬入前必须预约目标格位。
 *
 * <p>status: RESERVED 已预约 / MOVING 已开工 / COMPLETED 已完成
 * / CANCELLED 已取消 / EXPIRED 超时释放。</p>
 *
 * <p>{@code requestNo} 请求流水号唯一：补传/补录/重试复用同一流水号，只形成一条结果。
 * {@code holdKey} 稀疏唯一：在途时等于目标格位 id，终态置 NULL，
 * 保证同一格位同时只有一个在途预约。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_storage_reservation")
public class StorageReservation extends BaseEntity {

    /** 请求流水号：补传/补录/重试的幂等锚点 */
    private String requestNo;

    private String reservationNo;

    private Long areaId;

    private Long rackId;

    private Long targetCellId;

    /** 原位（新入库时可空） */
    private Long originCellId;

    private Long relicId;

    private String boxNo;

    private BigDecimal weightKg;

    private LocalDateTime planStartTime;

    private LocalDateTime planEndTime;

    private String reservedBy;

    /** 超时时刻：超时未开工自动释放 */
    private LocalDateTime expireAt;

    /** 实际操作人员（开工时登记） */
    private String operator;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    /** 在途持有键：=目标格位 id；终态为 NULL。受 uk_storage_cell_hold 唯一约束。
     *  终态要把该列写成 NULL，故更新策略必须始终拼接（默认 NOT_NULL 会漏掉置空）。 */
    @TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.IGNORED)
    private Long holdKey;

    /** RESERVED / MOVING / COMPLETED / CANCELLED / EXPIRED */
    private String status;

    private String cancelReason;
}
