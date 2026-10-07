package com.evops.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 协议变更：必须另出新版本（不覆盖已签协议），变更原因必填。
 * 条款字段与清单不传时沿用上一版本内容；传入时以新内容固化进新版本。
 */
@Data
public class AgreementAmendRequest {

    @NotBlank(message = "变更原因不能为空")
    private String changeReason;

    private String borrower;

    @DecimalMin(value = "0", message = "保险金额不能为负")
    private BigDecimal insuranceAmount;

    private String transportRoute;

    private LocalDate returnDate;

    private String terms;

    /** 不传则沿用上一版本清单；传入则作为新版本清单 */
    @Valid
    private List<AgreementManifestItem> items;
}
