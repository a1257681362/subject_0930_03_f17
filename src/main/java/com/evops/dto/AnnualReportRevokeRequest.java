package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/** 撤回已归档年度报表：只置 REVOKED，快照与凭证全部保留；重提另出新批次。 */
@Data
public class AnnualReportRevokeRequest {

    @NotBlank(message = "撤回原因不能为空")
    private String reason;

    @NotBlank(message = "撤回人不能为空")
    private String revokedBy;
}
