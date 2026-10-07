package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 归还验收差异补录：归还验收闭环后发现新的损伤/缺失事实时，
 * 在已完成的验收单上只追加差异行——协议与贯通借出单保持 RETURNED，
 * 不重新打开已完成归还；原验收比对行不改写。
 */
@Data
public class ReturnDiffSupplementRequest {

    /** 补录单号：全库唯一（幂等锚点），重复补录拒绝 */
    @NotBlank(message = "补录单号不能为空")
    private String supplementNo;

    @NotBlank(message = "补录人不能为空")
    private String supplementedBy;

    /** 原借展清单行 id（须属于封箱出库版本的清单） */
    @NotNull(message = "原清单行 id 不能为空")
    private Long manifestLineId;

    /** 补录的差异结果：DAMAGED 损伤 / MISSING 缺失（补录只登记差异，不登记相符） */
    @NotBlank(message = "补录差异结果不能为空")
    private String result;

    @NotBlank(message = "补录差异说明不能为空")
    private String diffNote;
}
