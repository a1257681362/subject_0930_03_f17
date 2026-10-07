package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.PestRulePublishRequest;
import com.evops.entity.PestRuleVersion;
import com.evops.service.PestRuleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 虫害阈值规则版本：规则调整只能发布新版本，旧版本自动 SUPERSEDED 只读。
 * 监测记录固化版本快照与结论，规则新版本不改写监测记录旧结论。
 */
@RestController
public class PestRuleController {

    private final PestRuleService ruleService;

    public PestRuleController(PestRuleService ruleService) {
        this.ruleService = ruleService;
    }

    /** 发布规则新版本（各虫种阈值数量） */
    @PostMapping("/api/pest-rules/publish")
    public ApiResponse<Map<String, Object>> publish(@Valid @RequestBody PestRulePublishRequest request) {
        return ApiResponse.ok(detail(ruleService.publish(request)));
    }

    /** 规则版本列表（只追加，含 SUPERSEDED 历史版本） */
    @GetMapping("/api/pest-rules")
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(ruleService.listVersions().stream()
                .map(this::detail).collect(Collectors.toList()));
    }

    /** 当前生效规则版本（未发布为 null） */
    @GetMapping("/api/pest-rules/active")
    public ApiResponse<Map<String, Object>> active() {
        PestRuleVersion version = ruleService.activeVersionEntity();
        return ApiResponse.ok(version == null ? null : detail(version));
    }

    /** 规则版本详情：版本头 + 各虫种阈值条目 */
    @GetMapping("/api/pest-rules/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(detail(ruleService.requireVersion(id)));
    }

    private Map<String, Object> detail(PestRuleVersion version) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("version", version);
        detail.put("items", ruleService.listItems(version.getId()));
        return detail;
    }
}
