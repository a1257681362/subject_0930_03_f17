package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 点位迁移：前后坐标都留痕（t_pest_point_move），点位行更新为当前坐标。
 */
@Data
public class PestPointMoveRequest {

    @NotNull(message = "迁移后 X 坐标不能为空")
    private BigDecimal toX;

    @NotNull(message = "迁移后 Y 坐标不能为空")
    private BigDecimal toY;

    private String reason;

    @NotBlank(message = "迁移人不能为空")
    private String movedBy;

    /** 迁移时间，缺省取当前时间 */
    private LocalDateTime movedAt;
}
