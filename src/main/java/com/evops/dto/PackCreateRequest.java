package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 装箱请求：申报箱号、运输气候区、防震（缓冲等级）要求与装箱实测重量/外形。
 * requiredCushionLevel 缺省按文物保存等级推导（一级 L1、二级 L2、三级 L3）。
 */
@Data
public class PackCreateRequest {

    /** 请求流水号：补传/补录/重试复用，全库唯一，命中同一装箱单原样回放 */
    private String requestNo;

    @NotNull(message = "文物不能为空")
    private Long relicId;

    /** 已签押运单（借展协议）id */
    @NotNull(message = "押运单（借展协议）不能为空")
    private Long agreementId;

    @NotBlank(message = "箱号不能为空")
    private String boxNo;

    @NotBlank(message = "运输气候区不能为空")
    @Pattern(regexp = "TEMPERATE|HOT_HUMID|COLD|DRY",
            message = "气候区只能为 TEMPERATE/HOT_HUMID/COLD/DRY")
    private String climateZone;

    /** 防震要求（缓冲等级下限），缺省按文物保存等级 */
    @Pattern(regexp = "L[123]", message = "缓冲等级只能为 L1/L2/L3")
    private String requiredCushionLevel;

    @NotNull(message = "装箱实测重量不能为空")
    @Positive(message = "装箱实测重量必须为正")
    private BigDecimal actualWeightG;

    @NotNull(message = "装箱实测长不能为空")
    @Positive(message = "外形必须为正")
    private BigDecimal actualLengthMm;
    @NotNull(message = "装箱实测宽不能为空")
    @Positive
    private BigDecimal actualWidthMm;
    @NotNull(message = "装箱实测厚不能为空")
    @Positive
    private BigDecimal actualDepthMm;

    @NotBlank(message = "装箱人不能为空")
    private String packedBy;

    private LocalDateTime sealedAt;
}
