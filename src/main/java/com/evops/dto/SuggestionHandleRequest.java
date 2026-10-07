package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 改期建议处理请求：采纳/忽略只记录处理意见，
 * 不回写工时明细行——原排程的变更只能走工时单新版本。
 */
@Data
public class SuggestionHandleRequest {

    @NotBlank(message = "处理人不能为空")
    private String handledBy;
}
