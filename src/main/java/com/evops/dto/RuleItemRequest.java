package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * 材质规则条目：温湿度/照度阈值与采样频率（分钟）。
 * 不同材质引用不同阈值：例如纸质对湿度与照度更严格、青铜对湿度更严格。
 */
@Data
public class RuleItemRequest {

    @NotBlank(message = "材质不能为空")
    private String material;

    @NotNull(message = "温度下限不能为空")
    private BigDecimal tempMin;

    @NotNull(message = "温度上限不能为空")
    private BigDecimal tempMax;

    @NotNull(message = "湿度下限不能为空")
    @DecimalMin(value = "0", message = "湿度下限不能小于0")
    private BigDecimal humidityMin;

    @NotNull(message = "湿度上限不能为空")
    @DecimalMin(value = "0", message = "湿度上限不能小于0")
    private BigDecimal humidityMax;

    @NotNull(message = "照度下限不能为空")
    @DecimalMin(value = "0", message = "照度下限不能小于0")
    private BigDecimal luxMin;

    @NotNull(message = "照度上限不能为空")
    @DecimalMin(value = "0", message = "照度上限不能小于0")
    private BigDecimal luxMax;

    /** 采样频率（分钟），按展厅生成任务时取各材质最小值 */
    @NotNull(message = "采样频率不能为空")
    @Positive(message = "采样频率必须为正整数分钟")
    private Integer sampleIntervalMin;
}
