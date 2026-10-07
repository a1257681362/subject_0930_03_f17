package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.dto.LoanRequest;
import com.evops.dto.ReturnRequest;
import com.evops.entity.LoanAgreement;
import com.evops.entity.Relic;
import com.evops.entity.RelicLoan;
import com.evops.mapper.LoanAgreementMapper;
import com.evops.mapper.RelicLoanMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static com.evops.constant.MuseumConstants.*;

/**
 * 借出 / 归还链路。
 * 一件文物同一时刻只允许一条未归还借出单；归还只更新原单状态，不新增、不抹除记录。
 */
@Service
public class LoanService {

    private final RelicLoanMapper loanMapper;
    private final LoanAgreementMapper agreementMapper;
    private final RelicService relicService;
    private final LedgerService ledgerService;

    public LoanService(RelicLoanMapper loanMapper,
                       LoanAgreementMapper agreementMapper,
                       RelicService relicService,
                       LedgerService ledgerService) {
        this.loanMapper = loanMapper;
        this.agreementMapper = agreementMapper;
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
}
