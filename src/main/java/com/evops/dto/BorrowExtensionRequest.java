package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * 归还延期申请（museum-10）：必须重新走完整审批链。
 */
@Data
public class BorrowExtensionRequest {

    @NotNull(message = "申请延期归还时间不能为空")
    private LocalDateTime requestedReturnAt;

    @NotBlank(message = "延期原因不能为空")
    private String reason;

    @NotBlank(message = "申请人不能为空")
    private String submittedBy;
}
