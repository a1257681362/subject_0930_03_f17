package com.evops.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.PositiveOrZero;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 虫害监测记录提交：虫种数量逐条登记，按提交时生效规则版本判定并固化结论。
 * 外包人员（OUTSOURCE）只能提交现场数据，不能关闭处置任务。
 * 监测结果按月份冻结：月份冻结后该月记录不再接受补录。
 */
@Data
public class PestRecordSubmitRequest {

    @NotNull(message = "点位不能为空")
    private Long pointId;

    /** 监测月份 yyyy-MM，缺省取提交时间所在月份 */
    @Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])$", message = "监测月份格式应为 yyyy-MM")
    private String monitorMonth;

    @NotBlank(message = "提交人不能为空")
    private String submittedBy;

    /** INTERNAL 馆内人员 / OUTSOURCE 外包人员 */
    @NotBlank(message = "提交人角色不能为空")
    private String submitterRole;

    /** 提交时间，缺省取当前时间 */
    private LocalDateTime submittedAt;

    @NotEmpty(message = "虫种数量不能为空")
    @Valid
    private List<SpeciesCount> details;

    @Data
    public static class SpeciesCount {

        @NotBlank(message = "虫种不能为空")
        private String pestSpecies;

        @NotNull(message = "虫种数量不能为空")
        @PositiveOrZero(message = "虫种数量不能为负")
        private Integer pestCount;
    }
}
