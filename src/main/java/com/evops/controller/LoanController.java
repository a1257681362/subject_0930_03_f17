package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.LoanRequest;
import com.evops.dto.ReturnRequest;
import com.evops.entity.RelicLoan;
import com.evops.service.LoanService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 借出 / 归还链路。
 * 外借清单的列表查询与 CSV 导出共用同一行集（同一状态源 t_relic_loan）。
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

    /**
     * 外借清单查询：可按文物 / 状态过滤（status=LENT 即在借清单）。
     * 与 CSV 导出共用同一行集同一状态源。
     */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestParam(value = "relicId", required = false) Long relicId,
            @RequestParam(value = "status", required = false) String status) {
        return ApiResponse.ok(loanService.listRows(relicId, status));
    }

    /** 外借清单 CSV 导出：与列表查询同一行集同一顺序（同一状态源） */
    @GetMapping("/export")
    public ResponseEntity<String> export(
            @RequestParam(value = "relicId", required = false) Long relicId,
            @RequestParam(value = "status", required = false) String status) {
        String csv = loanService.exportCsv(relicId, status);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=loans.csv")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv);
    }

    @GetMapping("/{id}")
    public ApiResponse<RelicLoan> get(@PathVariable Long id) {
        return ApiResponse.ok(loanService.requireLoan(id));
    }
}
