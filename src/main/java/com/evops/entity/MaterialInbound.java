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
 * 材料入库流水：每次入库（含同批号追加入库）追加一行，库存来源可追溯。
 */
@Data
@TableName("t_material_inbound")
public class MaterialInbound {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long batchId;

    private Long materialId;

    /** 本次入库数量 */
    private BigDecimal qty;

    private String supplier;

    private LocalDateTime inboundAt;

    private String inboundBy;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
