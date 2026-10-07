package com.evops.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * 工时明细行请求：一次作业的起止时间、工序与占用设备。
 * 起止可跨天，统计口径按自然日拆分。
 */
@Data
public class TimesheetLineRequest {

    @NotBlank(message = "工序不能为空")
    private String workStep;

    @NotNull(message = "占用设备不能为空")
    private Long equipmentId;

    @NotNull(message = "开始时间不能为空")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;

    @NotNull(message = "结束时间不能为空")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;
}
