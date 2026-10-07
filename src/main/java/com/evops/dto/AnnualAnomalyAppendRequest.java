package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 对已归档报表追加异常凭证：同一异常可多次追加（凭证序号递增），
 * 只追加，永不覆盖、不删除已有凭证。
 */
@Data
public class AnnualAnomalyAppendRequest {

    /** ENV_EVENT / EQUIPMENT_FAULT */
    @NotBlank(message = "异常类型不能为空")
    private String anomalyType;

    /** 异常来源行 id（t_env_event.id / t_repair_equipment_fault.id） */
    @NotNull(message = "异常来源 id 不能为空")
    private Long sourceId;

    /** 本次凭证内容/补充说明 */
    @NotBlank(message = "凭证内容不能为空")
    private String detail;

    @NotBlank(message = "追加人不能为空")
    private String appendedBy;
}
