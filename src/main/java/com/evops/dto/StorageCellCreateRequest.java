package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 格位建档：挂在密集架上，温区/防火等级默认取库区，可单独指定；承重上限必填。
 */
@Data
public class StorageCellCreateRequest {

    @NotBlank(message = "格位编号不能为空")
    private String cellNo;

    @NotNull(message = "所属密集架不能为空")
    private Long rackId;

    private Integer shelfNo;

    private Integer colNo;

    /** 不填则取所属库区温区 */
    private String tempZone;

    /** 不填则取所属库区防火等级 */
    private String fireRating;

    /** 格位承重上限（千克） */
    @NotNull(message = "格位承重上限不能为空")
    @DecimalMin(value = "0", message = "格位承重上限不能为负")
    private BigDecimal loadLimitKg;

    /** 是否敏感位置：低权限馆员查询时脱敏/过滤 */
    private Boolean sensitive = false;
}
