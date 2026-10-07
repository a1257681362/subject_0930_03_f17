package com.evops.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 库房台账：只追加、不修改、不删除。
 * 建档/停用、预约/取消/超时释放、移动开工/完成全部留痕。
 */
@Data
@TableName("t_storage_ledger")
public class StorageLedger {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long areaId;

    private Long rackId;

    private Long cellId;

    private Long reservationId;

    private Long orderId;

    /** CELL_MAINTAIN / RESERVE / RESERVE_CANCEL / RESERVE_EXPIRE / MOVE_START / MOVE_COMPLETE */
    private String action;

    private String bizNo;

    private String summary;

    private LocalDateTime occurredAt;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
