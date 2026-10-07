package com.evops.dto;

import lombok.Data;

import java.time.LocalDate;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class ProjectCreateRequest {

    @NotBlank(message = "项目编号不能为空")
    private String projectNo;

    @NotNull(message = "文物 id 不能为空")
    private Long relicId;

    @NotBlank(message = "委托人不能为空")
    private String client;

    private String clientContact;

    @NotBlank(message = "修复方案不能为空")
    private String plan;

    private LocalDate startDate;

    private LocalDate expectedEndDate;
}
