package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 修复设备：修复师按工单填报工时时引用的设备档案。
 * 状态：ACTIVE 在用 / FAULT 故障中；故障只置状态并生成改期建议，
 * 历史排程与工时事实永不删改。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_repair_equipment")
public class RepairEquipment extends BaseEntity {

    /** 设备编号，全局唯一 */
    private String equipmentNo;

    private String name;

    /** 设备类别（如 显微设备/激光设备/环境设备），自由文本 */
    private String category;

    private String location;

    /** ACTIVE / FAULT */
    private String status;

    private String remark;
}
