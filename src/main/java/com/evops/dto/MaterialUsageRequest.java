package com.evops.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 修复工单材料领用：一张单含至少一条明细；
 * 每行按批号领料并记录计划/实际用量、使用部位与操作者。
 */
@Data
public class MaterialUsageRequest {

    @NotBlank(message = "领用单号不能为空")
    private String usageNo;

    @NotNull(message = "修复项目 id 不能为空")
    private Long projectId;

    private LocalDateTime usageAt;

    private String note;

    @NotEmpty(message = "领用明细至少一行")
    @Valid
    private List<UsageLineRequest> lines;
}
