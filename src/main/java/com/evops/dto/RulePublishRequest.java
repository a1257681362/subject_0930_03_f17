package com.evops.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 发布环境巡检规则新版本：
 * 每个材质一条阈值与采样频率；发布后旧版本置 SUPERSEDED 只读，永不改写。
 */
@Data
public class RulePublishRequest {

    @NotBlank(message = "规则版本号不能为空")
    private String versionNo;

    @NotBlank(message = "规则标题不能为空")
    private String title;

    private String remark;

    @NotBlank(message = "发布人不能为空")
    private String publishedBy;

    @NotEmpty(message = "规则条目不能为空")
    @Valid
    private List<RuleItemRequest> items;
}
