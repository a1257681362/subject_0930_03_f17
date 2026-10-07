package com.evops.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 归还验收：必须对原借展清单逐项比对，不允许漏项或多项。
 */
@Data
public class ReturnCheckRequest {

    @NotBlank(message = "验收单号不能为空")
    private String checkNo;

    @NotBlank(message = "验收人不能为空")
    private String checkedBy;

    private LocalDateTime checkedAt;

    private String summary;

    @NotEmpty(message = "验收比对至少一行")
    @Valid
    private List<ReturnCheckItemDto> items;
}
