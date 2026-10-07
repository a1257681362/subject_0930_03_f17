package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.RulePublishRequest;
import com.evops.entity.EnvRuleVersion;
import com.evops.service.EnvRuleService;
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
 * 环境巡检规则版本：发布新版本（各材质阈值与采样频率），旧版本自动置 SUPERSEDED 只读。
 * 任务与读数固化版本快照，规则新版本不改写已完成巡检；报表标明规则版本。
 */
@RestController
public class EnvRuleController {

    private final EnvRuleService ruleService;

    public EnvRuleController(EnvRuleService ruleService) {
        this.ruleService = ruleService;
    }

    /** 发布规则新版本 */
    @PostMapping("/api/env-rules/publish")
    public ApiResponse<Map<String, Object>> publish(@Valid @RequestBody RulePublishRequest request) {
        EnvRuleVersion version = ruleService.publish(request);
        return ApiResponse.ok(detail(version));
    }

    /** 规则版本列表（只追加，含 SUPERSEDED 历史版本） */
    @GetMapping("/api/env-rules")
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(ruleService.listVersions().stream()
                .map(this::detail).collect(Collectors.toList()));
    }

    /** 当前生效规则版本（未发布为 null） */
    @GetMapping("/api/env-rules/active")
    public ApiResponse<Map<String, Object>> active() {
        EnvRuleVersion version = ruleService.activeVersionEntity();
        return ApiResponse.ok(version == null ? null : detail(version));
    }

    /** 规则版本详情：版本头 + 各材质条目 */
    @GetMapping("/api/env-rules/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(detail(ruleService.requireVersion(id)));
    }

    private Map<String, Object> detail(EnvRuleVersion version) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("version", version);
        detail.put("items", ruleService.listItems(version.getId()));
        return detail;
    }
}
