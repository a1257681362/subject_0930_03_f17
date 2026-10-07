package com.evops.dto;

import lombok.Data;

import java.time.LocalDateTime;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class LoanRequest {

    @NotBlank(message = "借出单号不能为空")
    private String loanNo;

    @NotNull(message = "文物 id 不能为空")
    private Long relicId;

    @NotBlank(message = "借用人/单位不能为空")
    private String borrower;

    private String purpose;

    private LocalDateTime lentAt;

    private LocalDateTime expectedReturnAt;
}
