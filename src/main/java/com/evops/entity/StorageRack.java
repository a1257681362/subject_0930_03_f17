package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 密集架（互斥设备）：同一架体同一时段只允许一个移动操作。
 * status: ACTIVE 在用 / CLOSED 已停用。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_storage_rack")
public class StorageRack extends BaseEntity {

    private String rackNo;

    private Long areaId;

    /** 互斥设备编号，全库唯一 */
    private String equipmentNo;

    private String name;

    private String aisle;

    /** ACTIVE / CLOSED */
    private String status;
}
