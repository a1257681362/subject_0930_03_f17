package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * 包装规格条目：材质 + 尺寸档（适用外形区间）+ 缓冲等级 + 气候区。
 */
@Data
public class SpecItemDto {

    @NotBlank(message = "材质不能为空")
    private String material;

    @NotBlank(message = "尺寸档编码不能为空")
    private String sizeCode;

    @NotNull(message = "适用长下限不能为空")
    @Positive(message = "适用外形必须为正")
    private BigDecimal fitLengthMinMm;
    @NotNull(message = "适用长上限不能为空")
    private BigDecimal fitLengthMaxMm;
    @NotNull(message = "适用宽下限不能为空")
    private BigDecimal fitWidthMinMm;
    @NotNull(message = "适用宽上限不能为空")
    private BigDecimal fitWidthMaxMm;
    @NotNull(message = "适用厚下限不能为空")
    private BigDecimal fitDepthMinMm;
    @NotNull(message = "适用厚上限不能为空")
    private BigDecimal fitDepthMaxMm;

    @NotNull(message = "承重上限不能为空")
    @Positive(message = "承重上限必须为正")
    private BigDecimal maxWeightG;

    /** 缓冲（防震）等级 L1 特级 / L2 标准 / L3 普通 */
    @NotBlank(message = "缓冲等级不能为空")
    @Pattern(regexp = "L[123]", message = "缓冲等级只能为 L1/L2/L3")
    private String cushionLevel;

    /** 允许冲击加速度上限（g，越小防震越严） */
    @NotNull(message = "防震冲击限值不能为空")
    @Positive(message = "防震冲击限值必须为正")
    private BigDecimal shockLimitG;

    /** 气候区 TEMPERATE/HOT_HUMID/COLD/DRY */
    @NotBlank(message = "气候区不能为空")
    @Pattern(regexp = "TEMPERATE|HOT_HUMID|COLD|DRY",
            message = "气候区只能为 TEMPERATE/HOT_HUMID/COLD/DRY")
    private String climateZone;
}
