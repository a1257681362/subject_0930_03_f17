package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 设备故障单：登记故障发生与预计恢复时刻。
 * 登记故障会自动为受影响的在排程工时行生成改期建议，
 * 但原排程（工时明细行）保持原样，绝不被悄悄改写。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_repair_equipment_fault")
public class RepairEquipmentFault extends BaseEntity {

    /** 故障单号 FT-000001，全局唯一 */
    private String faultNo;

    private Long equipmentId;

    private String description;

    /** 故障发生时刻 */
    private LocalDateTime faultAt;

    /** 预计恢复时刻，改期建议以此为新起点 */
    private LocalDateTime expectedRecoveredAt;

    /** OPEN / CLOSED */
    private String status;

    private String closedBy;

    private LocalDateTime closedAt;
}
