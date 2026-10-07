package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.dto.LoanRequest;
import com.evops.dto.ReturnRequest;
import com.evops.entity.BorrowApplication;
import com.evops.entity.LoanAgreement;
import com.evops.entity.Relic;
import com.evops.entity.RelicLoan;
import com.evops.mapper.BorrowApplicationMapper;
import com.evops.mapper.LoanAgreementMapper;
import com.evops.mapper.RelicLoanMapper;
import com.evops.mapper.RelicMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.evops.constant.MuseumConstants.*;

/**
 * 借出 / 归还链路。
 * 一件文物同一时刻只允许一条未归还借出单；归还只更新原单状态，不新增、不抹除记录。
 *
 * <p>借展协议（museum-6）与借阅审批（museum-10）封箱/出库时写入的贯通借出单，
 * 其闭环只能由所属业务链路完成（归还验收 / 借阅归还），旧通道不能提前归还释放占用；
 * 否则协议仍在途而占用已释放，文物会被重复借出，归还验收完成时贯通单已不在借，
 * 验收结论无处落写，文物在验收闭环后仍滞留外借清单。</p>
 *
 * <p>外借清单（{@link #listRows}）与 CSV 导出（{@link #exportCsv}）共用同一行集，
 * 直接读取 t_relic_loan 状态源，不做任何缓存，归还闭环即时可见。</p>
 */
@Service
public class LoanService {

    private static final DateTimeFormatter CSV_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final RelicLoanMapper loanMapper;
    private final LoanAgreementMapper agreementMapper;
    private final BorrowApplicationMapper borrowApplicationMapper;
    private final RelicMapper relicMapper;
    private final RelicService relicService;
    private final LedgerService ledgerService;

    public LoanService(RelicLoanMapper loanMapper,
                       LoanAgreementMapper agreementMapper,
                       BorrowApplicationMapper borrowApplicationMapper,
                       RelicMapper relicMapper,
                       RelicService relicService,
                       LedgerService ledgerService) {
        this.loanMapper = loanMapper;
        this.agreementMapper = agreementMapper;
        this.borrowApplicationMapper = borrowApplicationMapper;
        this.relicMapper = relicMapper;
        this.relicService = relicService;
        this.ledgerService = ledgerService;
    }

    @Transactional
    public RelicLoan lend(LoanRequest req) {
        Relic relic = relicService.requireActiveRelic(req.getRelicId());

        if (loanMapper.selectCount(new LambdaQueryWrapper<RelicLoan>()
                .eq(RelicLoan::getLoanNo, req.getLoanNo())) > 0) {
            throw new IllegalArgumentException("借出单号已存在: " + req.getLoanNo());
        }
        Long openCount = loanMapper.selectCount(new LambdaQueryWrapper<RelicLoan>()
                .eq(RelicLoan::getRelicId, req.getRelicId())
                .eq(RelicLoan::getStatus, LOAN_LENT));
        if (openCount != null && openCount > 0) {
            throw new IllegalArgumentException("文物仍有借出未归还，不能重复借出: " + relic.getRelicNo());
        }
        // museum-6：已有未闭环借展协议（封箱时会写贯通借出记录）时，旧通道不能再占用文物
        Long openAgreementCount = agreementMapper.selectCount(new LambdaQueryWrapper<LoanAgreement>()
                .eq(LoanAgreement::getRelicId, req.getRelicId())
                .notIn(LoanAgreement::getStatus, AGR_RETURNED, AGR_CANCELLED));
        if (openAgreementCount != null && openAgreementCount > 0) {
            throw new IllegalArgumentException("文物存在未闭环借展协议，不能通过旧借出通道重复借出: "
                    + relic.getRelicNo());
        }

        LocalDateTime lentAt = req.getLentAt() == null ? LocalDateTime.now() : req.getLentAt();
        RelicLoan loan = new RelicLoan();
        loan.setLoanNo(req.getLoanNo());
        loan.setRelicId(req.getRelicId());
        loan.setBorrower(req.getBorrower());
        loan.setPurpose(req.getPurpose());
        loan.setLentAt(lentAt);
        loan.setExpectedReturnAt(req.getExpectedReturnAt());
        loan.setStatus(LOAN_LENT);
        loanMapper.insert(loan);

        ledgerService.append(req.getRelicId(), null, loan.getId(),
                ACTION_LOAN_OUT, req.getLoanNo(),
                "借出给 " + req.getBorrower()
                        + (req.getPurpose() == null ? "" : "，用途：" + req.getPurpose()),
                lentAt);
        return loan;
    }

    @Transactional
    public RelicLoan repay(Long loanId, ReturnRequest req) {
        RelicLoan loan = requireLoan(loanId);
        if (!LOAN_LENT.equals(loan.getStatus())) {
            throw new IllegalArgumentException("借出单不是借出状态，不能归还: " + loan.getLoanNo());
        }
        // 贯通单只能由所属业务链路闭环：借展协议走归还验收，借阅审批走借阅归还。
        // 旧通道提前归还会绕开验收结论、提前释放占用，导致验收闭环后文物仍滞留外借清单。
        Long agreementOwned = agreementMapper.selectCount(new LambdaQueryWrapper<LoanAgreement>()
                .eq(LoanAgreement::getAgreementNo, loan.getLoanNo()));
        if (agreementOwned != null && agreementOwned > 0) {
            throw new IllegalArgumentException("该借出单是借展协议贯通单，须通过归还验收闭环，"
                    + "不能走旧借出通道归还: " + loan.getLoanNo());
        }
        Long borrowOwned = borrowApplicationMapper.selectCount(new LambdaQueryWrapper<BorrowApplication>()
                .eq(BorrowApplication::getApplicationNo, loan.getLoanNo()));
        if (borrowOwned != null && borrowOwned > 0) {
            throw new IllegalArgumentException("该借出单是借阅审批贯通单，须通过借阅归还闭环，"
                    + "不能走旧借出通道归还: " + loan.getLoanNo());
        }
        // 文物已撤销（理论上撤销前会被拦截，这里再兜底）
        relicService.requireRelic(loan.getRelicId());

        LocalDateTime returnedAt = req.getReturnedAt() == null ? LocalDateTime.now() : req.getReturnedAt();
        loan.setReturnedAt(returnedAt);
        loan.setReturnCondition(req.getReturnCondition());
        loan.setReceiver(req.getReceiver());
        loan.setStatus(LOAN_RETURNED);
        loanMapper.updateById(loan);

        ledgerService.append(loan.getRelicId(), null, loan.getId(),
                ACTION_RETURN, loan.getLoanNo(),
                "归还入库，接收人：" + (req.getReceiver() == null ? "未登记" : req.getReceiver())
                        + (req.getReturnCondition() == null ? "" : "，归还品相：" + req.getReturnCondition()),
                returnedAt);
        return loan;
    }

    public RelicLoan requireLoan(Long loanId) {
        RelicLoan loan = loanMapper.selectById(loanId);
        if (loan == null) {
            throw new IllegalArgumentException("借出单不存在: id=" + loanId);
        }
        return loan;
    }

    public List<RelicLoan> listByRelic(Long relicId) {
        return loanMapper.selectList(new LambdaQueryWrapper<RelicLoan>()
                .eq(RelicLoan::getRelicId, relicId)
                .orderByAsc(RelicLoan::getLentAt)
                .orderByAsc(RelicLoan::getId));
    }

    /**
     * 外借清单行集：页面查询与 CSV 导出共用同一行集同一顺序，
     * 直接读取 t_relic_loan 状态源（status=LENT 即当前在借清单），无缓存。
     */
    public List<Map<String, Object>> listRows(Long relicId, String status) {
        if (status != null && !status.isEmpty()
                && !LOAN_LENT.equals(status) && !LOAN_RETURNED.equals(status)
                && !LOAN_OVERDUE.equals(status)) {
            throw new IllegalArgumentException("未知借出状态: " + status + "（只能为 LENT/RETURNED/OVERDUE）");
        }
        List<RelicLoan> loans = loanMapper.selectList(new LambdaQueryWrapper<RelicLoan>()
                .eq(relicId != null, RelicLoan::getRelicId, relicId)
                .eq(status != null && !status.isEmpty(), RelicLoan::getStatus, status)
                .orderByAsc(RelicLoan::getId));
        Map<Long, Relic> relics = new HashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RelicLoan loan : loans) {
            Relic relic = relics.computeIfAbsent(loan.getRelicId(), relicMapper::selectById);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("loanId", loan.getId());
            row.put("loanNo", loan.getLoanNo());
            row.put("relicId", loan.getRelicId());
            row.put("relicNo", relic == null ? null : relic.getRelicNo());
            row.put("relicName", relic == null ? null : relic.getName());
            row.put("borrower", loan.getBorrower());
            row.put("purpose", loan.getPurpose());
            row.put("lentAt", loan.getLentAt());
            row.put("expectedReturnAt", loan.getExpectedReturnAt());
            row.put("returnedAt", loan.getReturnedAt());
            row.put("returnCondition", loan.getReturnCondition());
            row.put("receiver", loan.getReceiver());
            row.put("status", loan.getStatus());
            rows.add(row);
        }
        return rows;
    }

    /**
     * CSV 导出：与列表查询同一行集同一顺序，列口径固定。
     * 带 BOM，便于 Excel 直接打开中文。
     */
    public String exportCsv(Long relicId, String status) {
        List<Map<String, Object>> rows = listRows(relicId, status);
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append("借出单号,文物编号,文物名称,借用人,用途,借出时间,应还时间,归还时间,归还品相,接收人,状态\n");
        for (Map<String, Object> row : rows) {
            csv.append(csvCell(row.get("loanNo"))).append(',')
                    .append(csvCell(row.get("relicNo"))).append(',')
                    .append(csvCell(row.get("relicName"))).append(',')
                    .append(csvCell(row.get("borrower"))).append(',')
                    .append(csvCell(row.get("purpose"))).append(',')
                    .append(formatTime(row.get("lentAt"))).append(',')
                    .append(formatTime(row.get("expectedReturnAt"))).append(',')
                    .append(formatTime(row.get("returnedAt"))).append(',')
                    .append(csvCell(row.get("returnCondition"))).append(',')
                    .append(csvCell(row.get("receiver"))).append(',')
                    .append(csvCell(row.get("status"))).append('\n');
        }
        return csv.toString();
    }

    private String formatTime(Object value) {
        return value == null ? "" : ((LocalDateTime) value).format(CSV_TIME);
    }

    private String csvCell(Object value) {
        if (value == null) {
            return "";
        }
        String s = value.toString();
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }
}
