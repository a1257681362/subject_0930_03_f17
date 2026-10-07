package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 设置开放展示标记：仅成果验收通过（PASSED）后允许；
 * 公众页面只展示此处登记的对外摘要与授权影像子集。
 */
@Data
public class OpenDisplaySetRequest {

    /** 对外摘要（公众页面展示口径） */
    @NotBlank(message = "对外摘要不能为空")
    private String summary;

    /** 授权公开的影像 id 子集（须属于该文物且有效） */
    @NotEmpty(message = "授权影像不能为空")
    private List<Long> imageIds;

    /** 设置人 */
    @NotBlank(message = "设置人不能为空")
    private String grantedBy;
}
