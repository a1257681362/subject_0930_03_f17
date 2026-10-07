package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.service.OpenDisplayService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 公众展示页面（只读）：只展示已授权开放的内容——
 * 生效中的开放标记、其对外摘要与授权影像子集；
 * 未开放 / 已撤销的文物对公众不可见。
 */
@RestController
@RequestMapping("/api/public/open-displays")
public class PublicDisplayController {

    private final OpenDisplayService openDisplayService;

    public PublicDisplayController(OpenDisplayService openDisplayService) {
        this.openDisplayService = openDisplayService;
    }

    /** 公众列表：仅生效中的开放标记 */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(openDisplayService.publicList());
    }

    /** 公众详情：按文物查生效中的开放标记（摘要 + 授权影像） */
    @GetMapping("/{relicId}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long relicId) {
        return ApiResponse.ok(openDisplayService.publicDetail(relicId));
    }
}
