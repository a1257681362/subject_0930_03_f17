package com.evops.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 退料单：必须关联原领用单与原明细行，库存加回原批号。
 * 允许分次退料，但同一行累计退料不得超过该行实际用量；退料只追加，不改写原领用事实。
 */
@Data
@TableName("t_material_return")
public class MaterialReturn {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 退料单号，全局唯一 */
    private String returnNo;

    /** 原领用单 */
    private Long usageId;

    /** 原领用明细行 */
    private Long lineId;

    /** 库存加回的原批号 */
    private Long batchId;

    private Long projectId;

    private Long relicId;

    private BigDecimal returnQty;

    private String reason;

    /** 退料操作者 */
    private String returnedBy;

    private LocalDateTime returnedAt;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
