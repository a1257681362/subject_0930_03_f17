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
 * 点位迁移记录：只追加。保留迁移前后坐标，点位行更新为当前坐标。
 */
@Data
@TableName("t_pest_point_move")
public class PestPointMove {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long pointId;

    /** 迁移前坐标 */
    private BigDecimal fromX;
    private BigDecimal fromY;

    /** 迁移后坐标 */
    private BigDecimal toX;
    private BigDecimal toY;

    private String reason;

    private String movedBy;

    private LocalDateTime movedAt;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
