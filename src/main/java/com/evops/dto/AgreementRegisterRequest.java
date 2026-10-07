package com.evops.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 借展协议登记：借展方、保险金额、运输路线、归还日期与借展清单。
 * 登记生成协议主单（DRAFT）与 v1（DRAFT），签署后才生效。
 */
@Data
public class AgreementRegisterRequest {

    @NotBlank(message = "协议单号不能为空")
    private String agreementNo;

    @NotNull(message = "文物 id 不能为空")
    private Long relicId;

    @NotBlank(message = "借展方不能为空")
    private String borrower;

    @NotNull(message = "保险金额不能为空")
    @DecimalMin(value = "0", message = "保险金额不能为负")
    private BigDecimal insuranceAmount;

    @NotBlank(message = "运输路线不能为空")
    private String transportRoute;

    @NotNull(message = "归还日期不能为空")
    private LocalDate returnDate;

    /** 借展用途 */
    private String purpose;

    /** 其他协议条款 */
    private String terms;

    @NotEmpty(message = "借展清单至少一行")
    @Valid
    private List<AgreementManifestItem> items;
}
