package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 当前库存占用：一箱一行、一格一行。完成移动时原子清原格、立目的格。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_storage_stock")
public class StorageStock extends BaseEntity {

    private Long cellId;

    private String boxNo;

    private Long relicId;

    private Long reservationId;

    private LocalDateTime inboundAt;
}
