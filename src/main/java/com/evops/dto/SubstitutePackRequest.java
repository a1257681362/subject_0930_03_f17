package com.evops.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * 临时替代包装装箱申请：在标准装箱申报之外，登记替代原因、申请人与替代包装
 * 实际能力（尺寸区间/承重/缓冲/防震/气候）。与标准规格的逐项差异由系统生成
 * 并随审批单固化；替代包装必须先审批，驳回则装箱单终态 REJECTED。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SubstitutePackRequest extends PackCreateRequest {

    @NotBlank(message = "临时替代原因不能为空")
    private String reason;

    @NotBlank(message = "申请人不能为空")
    private String applicant;

    @NotBlank(message = "替代包装尺寸档不能为空")
    private String subSizeCode;

    @NotBlank(message = "替代包装缓冲等级不能为空")
    @Pattern(regexp = "L[123]", message = "缓冲等级只能为 L1/L2/L3")
    private String subCushionLevel;

    @NotBlank(message = "替代包装气候区不能为空")
    @Pattern(regexp = "TEMPERATE|HOT_HUMID|COLD|DRY",
            message = "气候区只能为 TEMPERATE/HOT_HUMID/COLD/DRY")
    private String subClimateZone;

    @NotNull(message = "替代包装承重上限不能为空")
    @Positive(message = "承重上限必须为正")
    private BigDecimal subMaxWeightG;

    @NotNull(message = "替代包装防震冲击限值不能为空")
    @Positive(message = "防震冲击限值必须为正")
    private BigDecimal subShockLimitG;

    @NotNull(message = "替代包装适用长下限不能为空")
    @Positive
    private BigDecimal subFitLengthMinMm;
    @NotNull(message = "替代包装适用长上限不能为空")
    @Positive
    private BigDecimal subFitLengthMaxMm;
    @NotNull(message = "替代包装适用宽下限不能为空")
    @Positive
    private BigDecimal subFitWidthMinMm;
    @NotNull(message = "替代包装适用宽上限不能为空")
    @Positive
    private BigDecimal subFitWidthMaxMm;
    @NotNull(message = "替代包装适用厚下限不能为空")
    @Positive
    private BigDecimal subFitDepthMinMm;
    @NotNull(message = "替代包装适用厚上限不能为空")
    @Positive
    private BigDecimal subFitDepthMaxMm;
}
