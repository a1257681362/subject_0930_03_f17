package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 诱捕点位建档：展厅/库房布放，登记编号、布放日期、坐标与责任区域。
 */
@Data
public class PestPointCreateRequest {

    @NotBlank(message = "点位编号不能为空")
    private String pointNo;

    /** HALL 展厅 / STORAGE 库房 */
    @NotBlank(message = "区域类型不能为空")
    private String zoneType;

    @NotNull(message = "区域不能为空")
    private Long zoneId;

    @NotBlank(message = "责任区域不能为空")
    private String responsibleArea;

    @NotNull(message = "X 坐标不能为空")
    private BigDecimal coordX;

    @NotNull(message = "Y 坐标不能为空")
    private BigDecimal coordY;

    @NotNull(message = "布放日期不能为空")
    private LocalDateTime deployedAt;

    @NotBlank(message = "布放人不能为空")
    private String deployedBy;
}
