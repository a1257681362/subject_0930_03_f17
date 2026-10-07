package com.evops.service;

import com.evops.entity.StorageLedger;
import com.evops.mapper.StorageLedgerMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 库房台账：只追加。建档停用、预约、取消、超时释放、移动开工/完成每一步都在此留痕。
 */
@Service
public class StorageLedgerService {

    private final StorageLedgerMapper ledgerMapper;

    public StorageLedgerService(StorageLedgerMapper ledgerMapper) {
        this.ledgerMapper = ledgerMapper;
    }

    public void append(Long areaId, Long rackId, Long cellId, Long reservationId, Long orderId,
                       String action, String bizNo, String summary, LocalDateTime occurredAt) {
        StorageLedger entry = new StorageLedger();
        entry.setAreaId(areaId);
        entry.setRackId(rackId);
        entry.setCellId(cellId);
        entry.setReservationId(reservationId);
        entry.setOrderId(orderId);
        entry.setAction(action);
        entry.setBizNo(bizNo);
        entry.setSummary(summary);
        entry.setOccurredAt(occurredAt == null ? LocalDateTime.now() : occurredAt);
        ledgerMapper.insert(entry);
    }

    public List<StorageLedger> listByReservation(Long reservationId) {
        return ledgerMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StorageLedger>()
                        .eq(StorageLedger::getReservationId, reservationId)
                        .orderByAsc(StorageLedger::getOccurredAt)
                        .orderByAsc(StorageLedger::getId));
    }
}
