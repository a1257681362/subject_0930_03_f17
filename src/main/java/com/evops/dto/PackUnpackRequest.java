package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import java.time.LocalDateTime;

/**
 * 拆箱包装状态回写：只回写拆箱字段，不覆盖发运前证据。
 * condition: INTACT 包装完好 / DAMAGED 包装受损（受损必须填说明）。
 */
@Data
public class PackUnpackRequest {

    @NotBlank(message = "拆箱人不能为空")
    private String unpackedBy;

    @NotNull(message = "拆箱包装状态不能为空")
    @Pattern(regexp = "INTACT|DAMAGED", message = "拆箱包装状态只能为 INTACT/DAMAGED")
    private String condition;

    private String note;

    private LocalDateTime unpackedAt;
}
