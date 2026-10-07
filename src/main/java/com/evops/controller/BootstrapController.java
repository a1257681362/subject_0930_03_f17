package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.BootstrapResult;
import com.evops.service.BootstrapService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「博物馆文物修复档案起步」。
 * POST 首次播种有效样本；重复 POST 返回同一条样本，不产生第二条结果。
 */
@RestController
@RequestMapping("/api/bootstrap")
public class BootstrapController {

    private final BootstrapService bootstrapService;

    public BootstrapController(BootstrapService bootstrapService) {
        this.bootstrapService = bootstrapService;
    }

    @PostMapping
    public ApiResponse<BootstrapResult> bootstrap() {
        return ApiResponse.ok(bootstrapService.bootstrap());
    }

    /** 只查看当前起步样本，不播种；未起步时 data 为 null */
    @GetMapping
    public ApiResponse<BootstrapResult> peek() {
        return ApiResponse.ok(bootstrapService.peek());
    }
}
