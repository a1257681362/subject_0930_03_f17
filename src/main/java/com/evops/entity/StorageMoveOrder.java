package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 移动单：一预约一条，固化原位/目的位/箱号/操作人员快照。
 * status: MOVING 移动中 / COMPLETED 已完成 / CANCELLED 已取消。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_storage_move_order")
public class StorageMoveOrder extends BaseEntity {

    private String orderNo;

    private Long reservationId;

    private Long originCellId;

    private Long targetCellId;

    /** 原位编号快照（新入库为 NULL） */
    private String originCellNo;

    private String targetCellNo;

    private String boxNo;

    private Long relicId;

    /** 操作人员 */
    private String operator;

    private LocalDateTime planStartTime;

    private LocalDateTime planEndTime;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    /** MOVING / COMPLETED / CANCELLED */
    private String status;
}
