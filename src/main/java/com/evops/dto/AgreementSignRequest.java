package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.time.LocalDateTime;

/**
 * 协议签署：仅当前 DRAFT 版本可签；签署后版本与主单生效，已签内容不可覆盖。
 */
@Data
public class AgreementSignRequest {

    @NotBlank(message = "签署人不能为空")
    private String signedBy;

    private LocalDateTime signedAt;
}
