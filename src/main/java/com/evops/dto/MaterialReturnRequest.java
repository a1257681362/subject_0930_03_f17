package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 退料：必须关联原领用单与原明细行，库存加回原批号。
 * 允许分次退料，累计退料不得超过该行实际用量。
 */
@Data
public class MaterialReturnRequest {

    @NotBlank(message = "退料单号不能为空")
    private String returnNo;

    @NotNull(message = "原领用单 id 不能为空")
    private Long usageId;

    @NotNull(message = "原领用明细行 id 不能为空")
    private Long lineId;

    @NotNull(message = "退料数量不能为空")
    @DecimalMin(value = "0.001", message = "退料数量必须大于 0")
    private BigDecimal returnQty;

    private String reason;

    @NotBlank(message = "退料操作者不能为空")
    private String returnedBy;
}
