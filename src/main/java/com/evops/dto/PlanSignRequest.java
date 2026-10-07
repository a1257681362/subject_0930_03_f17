package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 会签签署：实签人必须是当前步骤指定的应签人本人，缺席不允许代签。
 */
@Data
public class PlanSignRequest {

    /** 签署角色：INTERNAL / EXTERNAL / CUSTODIAN，必须与当前应签步骤一致 */
    @NotBlank(message = "签署角色不能为空")
    private String signerRole;

    /** 实签人姓名，必须与版本指定的应签人一致 */
    @NotBlank(message = "实签人不能为空")
    private String signerName;

    /** APPROVE 同意 / RETURN 退回 */
    @NotBlank(message = "会签决定不能为空")
    private String decision;

    /** 会签意见 */
    private String opinion;

    /** 依据附件 */
    private String attachment;
}
