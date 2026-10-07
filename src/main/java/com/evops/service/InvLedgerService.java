package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.entity.InvLedger;
import com.evops.mapper.InvLedgerMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 清点台账：只追加、不修改、不删除。
 * 任务生成/扫描/重基线回放/差异单开立与复核/任务关闭/补充记录/展厅关闭全部留痕。
 */
@Service
public class InvLedgerService {

    private final InvLedgerMapper ledgerMapper;

    public InvLedgerService(InvLedgerMapper ledgerMapper) {
        this.ledgerMapper = ledgerMapper;
    }

    public void append(Long hallId, Long taskId, Long diffId,
                       String action, String bizNo, String summary, LocalDateTime occurredAt) {
        InvLedger entry = new InvLedger();
        entry.setHallId(hallId);
        entry.setTaskId(taskId);
        entry.setDiffId(diffId);
        entry.setAction(action);
        entry.setBizNo(bizNo);
        entry.setSummary(summary);
        entry.setOccurredAt(occurredAt == null ? LocalDateTime.now() : occurredAt);
        ledgerMapper.insert(entry);
    }

    /** 按展厅时间序列查询（不传展厅为全馆） */
    public List<InvLedger> list(Long hallId) {
        return ledgerMapper.selectList(new LambdaQueryWrapper<InvLedger>()
                .eq(hallId != null, InvLedger::getHallId, hallId)
                .orderByAsc(InvLedger::getOccurredAt)
                .orderByAsc(InvLedger::getId));
    }

    public List<InvLedger> listByTask(Long taskId) {
        return ledgerMapper.selectList(new LambdaQueryWrapper<InvLedger>()
                .eq(InvLedger::getTaskId, taskId)
                .orderByAsc(InvLedger::getOccurredAt)
                .orderByAsc(InvLedger::getId));
    }
}
