package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/** 关闭清点任务：差异全部复核完成后方可关闭 */
@Data
public class InvTaskCloseRequest {

    @NotBlank(message = "关闭人不能为空")
    private String closedBy;
}
