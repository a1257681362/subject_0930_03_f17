package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 领用明细行：按批号领料，固化用量、部位、操作者。
 * 操作者是实际用料责任人，必填，任何后续流程都不得覆盖。
 */
@Data
public class UsageLineRequest {

    @NotBlank(message = "批号不能为空")
    private String batchNo;

    @NotNull(message = "计划用量不能为空")
    @DecimalMin(value = "0", message = "计划用量不能为负")
    private BigDecimal planQty;

    @NotNull(message = "实际用量不能为空")
    @DecimalMin(value = "0", message = "实际用量不能为负")
    private BigDecimal actualQty;

    @NotBlank(message = "使用部位不能为空")
    private String applyPart;

    @NotBlank(message = "操作者不能为空")
    private String operator;
}
