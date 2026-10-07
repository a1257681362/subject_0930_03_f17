package com.evops.service;

import com.evops.entity.AppraisalReportAudit;
import com.evops.mapper.AppraisalReportAuditMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static com.evops.constant.MuseumConstants.*;

/**
 * 详细报告下载审计：独立事务写入。
 * 权限不足被拒绝时主业务事务会回滚并抛错，但审计流水与台账必须保留，
 * 因此这里强制 REQUIRES_NEW：无论调用方事务最终提交还是回滚，
 * GRANTED / DENIED 都已经独立落库。
 */
@Service
public class AppraisalReportAuditService {

    private final AppraisalReportAuditMapper auditMapper;
    private final LedgerService ledgerService;

    public AppraisalReportAuditService(AppraisalReportAuditMapper auditMapper,
                                       LedgerService ledgerService) {
        this.auditMapper = auditMapper;
        this.ledgerService = ledgerService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AppraisalReportAudit record(Long caseId, Long relicId, String caseNo,
                                       String operatorName, String operatorRole,
                                       boolean granted, String reason, String clientIp) {
        LocalDateTime now = LocalDateTime.now();
        AppraisalReportAudit audit = new AppraisalReportAudit();
        audit.setCaseId(caseId);
        audit.setOperatorName(operatorName);
        audit.setOperatorRole(operatorRole);
        audit.setResult(granted ? REPORT_GRANTED : REPORT_DENIED);
        audit.setReason(reason);
        audit.setClientIp(clientIp);
        audit.setDownloadedAt(now);
        auditMapper.insert(audit);

        ledgerService.append(relicId, null, null,
                granted ? ACTION_APPRAISAL_REPORT_DOWNLOAD : ACTION_APPRAISAL_REPORT_DENY,
                caseNo,
                (granted ? "详细鉴定报告下载放行：" : "详细鉴定报告下载被拒绝：")
                        + operatorName + "（" + operatorRole + "）"
                        + (reason == null ? "" : "，" + reason), now);
        return audit;
    }
}
