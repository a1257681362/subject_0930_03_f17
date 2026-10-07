package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 材料按批号入库：同一材料同一批号重复入库时累加库存并追加一条入库流水。
 */
@Data
public class MaterialInboundRequest {

    @NotBlank(message = "材料编号不能为空")
    private String materialNo;

    @NotBlank(message = "批号不能为空")
    private String batchNo;

    @NotNull(message = "入库数量不能为空")
    @DecimalMin(value = "0.001", message = "入库数量必须大于 0")
    private BigDecimal qty;

    private LocalDate producedDate;

    /** 到期日期；早于领用当天即过期，禁止领用 */
    private LocalDate expireDate;

    private String supplier;

    private String inboundBy;
}
