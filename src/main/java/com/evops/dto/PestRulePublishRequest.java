package com.evops.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.PositiveOrZero;
import java.util.List;

/**
 * 发布虫害阈值规则新版本：每个虫种一条阈值。
 * 发布后旧版本置 SUPERSEDED 只读；规则调整只能生成新版本，不改写监测记录旧结论。
 */
@Data
public class PestRulePublishRequest {

    @NotBlank(message = "规则版本号不能为空")
    private String versionNo;

    @NotBlank(message = "规则标题不能为空")
    private String title;

    private String remark;

    @NotBlank(message = "发布人不能为空")
    private String publishedBy;

    @NotEmpty(message = "规则条目不能为空")
    @Valid
    private List<Item> items;

    @Data
    public static class Item {

        @NotBlank(message = "虫种不能为空")
        private String pestSpecies;

        @NotNull(message = "阈值数量不能为空")
        @PositiveOrZero(message = "阈值数量不能为负")
        private Integer thresholdCount;
    }
}
