package com.evops.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 目标格位预约请求：搬入前必须先预约。
 * 同一 requestNo 的补传/补录/重试只形成一条可用结果。
 */
@Data
public class StorageReservationCreateRequest {

    /** 请求流水号：补传/补录/重试复用同一号，全库唯一（幂等锚点） */
    @NotBlank(message = "请求流水号不能为空")
    private String requestNo;

    @NotNull(message = "目标格位不能为空")
    private Long targetCellId;

    /** 原格位：新入库时可空；库内移动时必填 */
    private Long originCellId;

    private Long relicId;

    @NotBlank(message = "箱号不能为空")
    private String boxNo;

    /** 箱体重量（千克），用于格位承重校验；可空 */
    private BigDecimal weightKg;

    @NotNull(message = "计划开始时间不能为空")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime planStartTime;

    @NotNull(message = "计划结束时间不能为空")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime planEndTime;

    @NotBlank(message = "预约人不能为空")
    private String reservedBy;

    /** 预约保留分钟数：不填取系统配置 museum.storage.reservation-expire-minutes */
    private Long expireMinutes;
}
