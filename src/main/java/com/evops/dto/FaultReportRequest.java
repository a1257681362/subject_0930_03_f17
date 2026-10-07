package com.evops.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * 设备故障登记请求：登记后设备置为故障中，
 * 自动为受影响工时行生成改期建议（不改写原排程）。
 */
@Data
public class FaultReportRequest {

    @NotBlank(message = "故障描述不能为空")
    private String description;

    /** 故障发生时刻：不填取当前时刻 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime faultAt;

    /** 预计恢复时刻：改期建议以此为新起点 */
    @NotNull(message = "预计恢复时刻不能为空")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime expectedRecoveredAt;
}
