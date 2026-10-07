package com.evops.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static com.evops.constant.MuseumConstants.ACTION_VAL_ACCESS_DENY;

/**
 * 保险估值敏感字段访问拒绝流水（museum-15）。
 *
 * <p>独立事务（REQUIRES_NEW）：即使外层查询是只读事务或随后抛出访问拒绝异常，
 * 拒绝流水也必须落库保留——公众/普通馆员每次触碰敏感估值入口都可追溯。</p>
 */
@Service
public class ValuationAccessAuditService {

    private final LedgerService ledgerService;

    public ValuationAccessAuditService(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDeny(Long relicId, String bizNo, String reason) {
        ledgerService.append(relicId, null, null,
                ACTION_VAL_ACCESS_DENY, bizNo, reason, LocalDateTime.now());
    }
}
