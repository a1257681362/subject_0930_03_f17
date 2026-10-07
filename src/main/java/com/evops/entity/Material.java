package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 修复材料目录：颜料 / 黏合剂 / 保护剂。
 * 目录只描述材料本身，库存按批号管理；停用不影响历史领用事实。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_material")
public class Material extends BaseEntity {

    /** 材料编号，全局唯一 */
    private String materialNo;

    private String name;

    /** PIGMENT 颜料 / ADHESIVE 黏合剂 / PROTECTANT 保护剂 */
    private String category;

    /** 计量单位，如 g / ml */
    private String unit;

    /** 规格说明 */
    private String spec;

    /** ACTIVE 在用 / DISABLED 停用 */
    private String status;
}
