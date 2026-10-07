package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/** 借阅申请撤销（申请人主动；PENDING 可撤，存在提前收回补偿时 APPROVED 未出库也可撤） */
@Data
public class BorrowCancelRequest {

    @NotBlank(message = "撤销操作人不能为空")
    private String operator;

    @NotBlank(message = "撤销原因不能为空")
    private String reason;
}
