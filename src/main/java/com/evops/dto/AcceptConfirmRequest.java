package com.evops.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.util.List;

/**
 * 成果验收逐项确认：馆内专家 / 保管员分别对本角色全部清单项一次确认。
 * 任一项 MISSING 即整整改；确认人必须为报告指定确认人本人，不能代签。
 */
@Data
public class AcceptConfirmRequest {

    /** 确认角色：INTERNAL 馆内专家 / CUSTODIAN 保管员 */
    @NotBlank(message = "确认角色不能为空")
    private String role;

    /** 确认人（须为报告指定确认人本人） */
    @NotBlank(message = "确认人不能为空")
    private String confirmer;

    /** 逐项确认结果：必须覆盖报告全部清单项 */
    @NotEmpty(message = "逐项确认结果不能为空")
    @Valid
    private List<ItemResult> items;

    /** 确认意见 */
    private String opinion;

    @Data
    public static class ItemResult {
        @NotNull(message = "清单项ID不能为空")
        private Long itemId;

        /** OK 合格 / MISSING 缺项 */
        @NotBlank(message = "确认结果不能为空")
        private String result;
    }
}
