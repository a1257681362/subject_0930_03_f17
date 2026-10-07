package com.evops.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 包装规格版本发布：规格按材质、尺寸档、缓冲等级、气候区配置条目。
 * 发布新版本后旧生效版本置 SUPERSEDED，只追加不改写。
 */
@Data
public class PackSpecPublishRequest {

    @NotBlank(message = "规格版本号不能为空")
    private String versionNo;

    @NotBlank(message = "规格标题不能为空")
    private String title;

    private String remark;

    @NotBlank(message = "发布人不能为空")
    private String publishedBy;

    @NotEmpty(message = "规格条目不能为空")
    @Valid
    private List<SpecItemDto> items;
}
