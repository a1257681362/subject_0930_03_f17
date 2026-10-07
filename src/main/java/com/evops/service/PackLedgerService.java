package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.entity.PackLedger;
import com.evops.mapper.PackLedgerMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 包装台账：只追加。规格发布/装箱/替代审批/外部通知/拆箱全部留痕。
 */
@Service
public class PackLedgerService {

    private final PackLedgerMapper ledgerMapper;

    public PackLedgerService(PackLedgerMapper ledgerMapper) {
        this.ledgerMapper = ledgerMapper;
    }

    public void append(Long relicId, Long orderId, Long substituteId,
                       Long specVersionId, String specVersionNo,
                       String action, String bizNo, String summary, LocalDateTime occurredAt) {
        PackLedger ledger = new PackLedger();
        ledger.setRelicId(relicId);
        ledger.setOrderId(orderId);
        ledger.setSubstituteId(substituteId);
        ledger.setSpecVersionId(specVersionId);
        ledger.setSpecVersionNo(specVersionNo);
        ledger.setAction(action);
        ledger.setBizNo(bizNo);
        ledger.setSummary(summary);
        ledger.setOccurredAt(occurredAt == null ? LocalDateTime.now() : occurredAt);
        ledgerMapper.insert(ledger);
    }

    public List<PackLedger> list(Long relicId, Long orderId) {
        return ledgerMapper.selectList(new LambdaQueryWrapper<PackLedger>()
                .eq(relicId != null, PackLedger::getRelicId, relicId)
                .eq(orderId != null, PackLedger::getOrderId, orderId)
                .orderByAsc(PackLedger::getOccurredAt)
                .orderByAsc(PackLedger::getId));
    }
}
