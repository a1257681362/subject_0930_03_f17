package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.entity.PestLedger;
import com.evops.mapper.PestLedgerMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 虫害台账：只追加、不修改、不删除。
 * 规则发布/点位建档迁移/诱捕片布放更换/监测提交/任务生成关闭/月份冻结全部留痕，
 * 每条尽量固化规则版本号——虫害监测全程可追溯。
 */
@Service
public class PestLedgerService {

    private final PestLedgerMapper ledgerMapper;

    public PestLedgerService(PestLedgerMapper ledgerMapper) {
        this.ledgerMapper = ledgerMapper;
    }

    public void append(Long pointId, Long recordId, Long taskId,
                       Long ruleVersionId, String ruleVersionNo,
                       String action, String bizNo, String summary, LocalDateTime occurredAt) {
        PestLedger entry = new PestLedger();
        entry.setPointId(pointId);
        entry.setRecordId(recordId);
        entry.setTaskId(taskId);
        entry.setRuleVersionId(ruleVersionId);
        entry.setRuleVersionNo(ruleVersionNo);
        entry.setAction(action);
        entry.setBizNo(bizNo);
        entry.setSummary(summary);
        entry.setOccurredAt(occurredAt == null ? LocalDateTime.now() : occurredAt);
        ledgerMapper.insert(entry);
    }

    /** 按点位时间序列查询（不传点位为全馆台账） */
    public List<PestLedger> list(Long pointId) {
        return ledgerMapper.selectList(new LambdaQueryWrapper<PestLedger>()
                .eq(pointId != null, PestLedger::getPointId, pointId)
                .orderByAsc(PestLedger::getOccurredAt)
                .orderByAsc(PestLedger::getId));
    }
}
