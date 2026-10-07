package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class StageRequest {

    @NotBlank(message = "阶段名称不能为空")
    private String stageName;

    @NotBlank(message = "阶段结果不能为空")
    private String stageResult;

    private String operator;

    /**
     * 客户端请求流水号（museum-11）：同一操作并发发起/重试/补传时复用，
     * 全表稀疏唯一，命中同一次尝试，不重复执行；不传则按普通请求处理。
     */
    private String requestNo;
}
