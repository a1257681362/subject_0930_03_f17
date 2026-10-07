package com.evops.service;

import com.evops.entity.AnnualReportLedger;
import com.evops.mapper.AnnualReportLedgerMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** 年度报表台账：口径发布/生成/撤回/重提/异常凭证/任务失败/告警确认全部只追加。 */
@Service
public class AnnualReportLedgerService {

    private final AnnualReportLedgerMapper ledgerMapper;

    public AnnualReportLedgerService(AnnualReportLedgerMapper ledgerMapper) {
        this.ledgerMapper = ledgerMapper;
    }

    public void append(Long reportId, Long caliberVersionId, String action,
                       String bizNo, String summary, LocalDateTime occurredAt) {
        AnnualReportLedger entry = new AnnualReportLedger();
        entry.setReportId(reportId);
        entry.setCaliberVersionId(caliberVersionId);
        entry.setAction(action);
        entry.setBizNo(bizNo);
        entry.setSummary(summary);
        entry.setOccurredAt(occurredAt == null ? LocalDateTime.now() : occurredAt);
        ledgerMapper.insert(entry);
    }
}
