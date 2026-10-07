package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.LoanRequest;
import com.evops.dto.ReturnRequest;
import com.evops.entity.RelicLoan;
import com.evops.service.LoanService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

/**
 * 借出 / 归还链路。
 */
@RestController
@RequestMapping("/api/loans")
public class LoanController {

    private final LoanService loanService;

    public LoanController(LoanService loanService) {
        this.loanService = loanService;
    }

    /** 借出 */
    @PostMapping
    public ApiResponse<RelicLoan> lend(@Valid @RequestBody LoanRequest request) {
        return ApiResponse.ok(loanService.lend(request));
    }

    /** 归还：在原借出单上闭环，不新增记录 */
    @PostMapping("/{id}/return")
    public ApiResponse<RelicLoan> repay(@PathVariable Long id,
                                        @RequestBody ReturnRequest request) {
        return ApiResponse.ok(loanService.repay(id, request));
    }

    @GetMapping("/{id}")
    public ApiResponse<RelicLoan> get(@PathVariable Long id) {
        return ApiResponse.ok(loanService.requireLoan(id));
    }
}
