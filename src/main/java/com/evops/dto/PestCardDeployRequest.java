package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * 布放/更换诱捕片：点位已有在布诱捕片时旧片置 REPLACED（不删除旧记录），新片在布。
 */
@Data
public class PestCardDeployRequest {

    @NotBlank(message = "诱捕片编号不能为空")
    private String cardNo;

    @NotNull(message = "布放日期不能为空")
    private LocalDateTime deployedAt;

    @NotBlank(message = "布放人不能为空")
    private String deployedBy;
}
