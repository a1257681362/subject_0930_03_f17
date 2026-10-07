package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

/**
 * 监测月份冻结：冻结后该月监测记录与照片不再接受补录/更改。
 * 冻结只追加，不解冻。
 */
@Data
public class PestMonthFreezeRequest {

    @NotBlank(message = "监测月份不能为空")
    @Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])$", message = "监测月份格式应为 yyyy-MM")
    private String monitorMonth;

    @NotBlank(message = "冻结人不能为空")
    private String frozenBy;
}
