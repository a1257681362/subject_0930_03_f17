package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;

/** 按展厅生成清点任务（一展厅同时只有一个在途任务，重复生成幂等） */
@Data
public class InvTaskGenerateRequest {

    @NotNull(message = "展厅id不能为空")
    private Long hallId;

    /** 任务登记人 */
    private String operatorName;
}
