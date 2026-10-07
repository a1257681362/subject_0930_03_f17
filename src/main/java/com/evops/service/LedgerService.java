package com.evops.service;

import com.evops.entity.LedgerEntry;
import com.evops.mapper.LedgerEntryMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 台账流水：只追加。借出、归还、修复、验收、入藏、撤销的每一步都在此留痕。
 */
@Service
public class LedgerService {

    private final LedgerEntryMapper ledgerMapper;

    public LedgerService(LedgerEntryMapper ledgerMapper) {
        this.ledgerMapper = ledgerMapper;
    }

    public void append(Long relicId, Long projectId, Long loanId,
                       String action, String bizNo, String summary, LocalDateTime occurredAt) {
        LedgerEntry entry = new LedgerEntry();
        entry.setRelicId(relicId);
        entry.setProjectId(projectId);
        entry.setLoanId(loanId);
        entry.setAction(action);
        entry.setBizNo(bizNo);
        entry.setSummary(summary);
        entry.setOccurredAt(occurredAt == null ? LocalDateTime.now() : occurredAt);
        ledgerMapper.insert(entry);
    }

    public List<LedgerEntry> listByRelic(Long relicId) {
        return ledgerMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LedgerEntry>()
                        .eq(LedgerEntry::getRelicId, relicId)
                        .orderByAsc(LedgerEntry::getOccurredAt)
                        .orderByAsc(LedgerEntry::getId));
    }
}
