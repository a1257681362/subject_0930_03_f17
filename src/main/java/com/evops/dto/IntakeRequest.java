package com.evops.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;

@Data
public class IntakeRequest {

    @NotBlank(message = "入藏单号不能为空")
    private String intakeNo;

    private BigDecimal heightMm;

    private BigDecimal widthMm;

    private BigDecimal depthMm;

    private BigDecimal weightG;

    @NotBlank(message = "库位不能为空")
    private String location;

    private LocalDateTime intakeTime;

    private String receiver;

    private String conditionNote;

    /** 关联照片，至少一张 */
    @NotEmpty(message = "入藏至少关联一张照片")
    @Valid
    private List<PhotoDto> photos;
}
