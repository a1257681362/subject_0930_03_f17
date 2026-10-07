package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.time.LocalDateTime;

/**
 * 借阅归还（museum-10）：在原申请上闭环，不新增借阅事实。
 * 若存在审批中的归还延期，自动驳回（须重新申请）。
 */
@Data
public class BorrowReturnRequest {

    @NotBlank(message = "归还接收人不能为空")
    private String returnedBy;

    private LocalDateTime returnedAt;

    private String returnCondition;
}
