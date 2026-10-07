package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 库区建档：按温区、防火等级、承重维护。
 */
@Data
public class StorageAreaCreateRequest {

    @NotBlank(message = "库区编号不能为空")
    private String areaNo;

    @NotBlank(message = "库区名称不能为空")
    private String name;

    /** 温区：CONST_TEMP 恒温 / COLD 冷藏 / ORDINARY 常温 */
    @NotBlank(message = "温区不能为空")
    private String tempZone;

    /** 防火等级：F1 / F2 / F3 */
    @NotBlank(message = "防火等级不能为空")
    private String fireRating;

    /** 库区承重（千克） */
    @NotNull(message = "库区承重不能为空")
    @DecimalMin(value = "0", message = "库区承重不能为负")
    private BigDecimal loadCapacityKg;

    private String location;

    private String manager;
}
