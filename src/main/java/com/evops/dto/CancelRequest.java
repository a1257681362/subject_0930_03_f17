package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class CancelRequest {

    @NotBlank(message = "作废原因不能为空")
    private String reason;

    /** 操作人（并发审计记录用，可空） */
    private String operatorName;

    /** 客户端请求流水号（museum-11）：并发/重试/补传复用，命中同一次尝试 */
    private String requestNo;
}
