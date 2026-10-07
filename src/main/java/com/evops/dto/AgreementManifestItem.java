package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 借展清单登记行：随协议首版/变更版固化为出库清单快照。
 */
@Data
public class AgreementManifestItem {

    @NotBlank(message = "清单条目编号不能为空")
    private String itemNo;

    @NotBlank(message = "清单条目名称不能为空")
    private String itemName;

    @NotNull(message = "数量不能为空")
    @Min(value = 1, message = "数量必须大于 0")
    private Integer qty;

    /** 出库品相/备注 */
    private String outCondition;
}
