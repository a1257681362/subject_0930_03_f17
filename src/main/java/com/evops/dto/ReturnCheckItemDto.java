package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 归还验收逐行比对项：引用原借展清单行。
 * result: INTACT 相符 / DAMAGED 损伤 / MISSING 缺失；非 INTACT 必须填差异说明。
 */
@Data
public class ReturnCheckItemDto {

    @NotNull(message = "原清单行 id 不能为空")
    private Long manifestLineId;

    @NotBlank(message = "比对结果不能为空")
    private String result;

    /** 差异说明（损伤/缺失时必填） */
    private String diffNote;
}
