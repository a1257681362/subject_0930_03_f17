package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.entity.RepairOperationAttempt;
import com.evops.entity.RepairProject;
import com.evops.mapper.RepairOperationAttemptMapper;
import com.evops.mapper.RepairProjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static com.evops.constant.MuseumConstants.*;

/**
 * 修复并发被拒尝试的独立审计：强制 REQUIRES_NEW。
 * 落败请求的业务事务已经回滚（状态/阶段/台账等业务写入全部撤销），
 * 但"谁被拒绝、为什么、最终谁生效"这条审计必须独立保留，
 * 因此不能挂在已回滚的业务事务上。
 */
@Service
public class RepairAttemptAuditService {

    private static final Logger log = LoggerFactory.getLogger(RepairAttemptAuditService.class);

    private final RepairOperationAttemptMapper attemptMapper;
    private final RepairProjectMapper projectMapper;
    private final LedgerService ledgerService;

    public RepairAttemptAuditService(RepairOperationAttemptMapper attemptMapper,
                                     RepairProjectMapper projectMapper,
                                     LedgerService ledgerService) {
        this.attemptMapper = attemptMapper;
        this.projectMapper = projectMapper;
        this.ledgerService = ledgerService;
    }

    /**
     * 记录一次被拒绝的并发尝试，并指向最终生效尝试（若已存在）。
     * 独立事务提交，不受调用方业务事务回滚影响。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRejected(Long projectId, String operation, String requestNo,
                               String operatorName, String reason) {
        LocalDateTime now = LocalDateTime.now();
        RepairOperationAttempt effective = attemptMapper.selectList(
                        new LambdaQueryWrapper<RepairOperationAttempt>()
                                .eq(RepairOperationAttempt::getProjectId, projectId)
                                .eq(RepairOperationAttempt::getOperation, operation)
                                .eq(RepairOperationAttempt::getDecision, ATTEMPT_EFFECTIVE)
                                .orderByDesc(RepairOperationAttempt::getId)
                                .last("LIMIT 1"))
                .stream().findFirst().orElse(null);

        RepairOperationAttempt attempt = new RepairOperationAttempt();
        attempt.setProjectId(projectId);
        attempt.setOperation(operation);
        attempt.setRequestNo(requestNo);
        attempt.setOperatorName(operatorName);
        attempt.setDecision(ATTEMPT_REJECTED);
        attempt.setRejectReason(reason);
        attempt.setEffectiveAttemptId(effective == null ? null : effective.getId());
        attempt.setOccurredAt(now);
        try {
            attemptMapper.insert(attempt);
        } catch (DuplicateKeyException e) {
            // 同请求流水号的拒绝记录已由并发对端写入，审计事实不重复即可
            log.warn("被拒尝试审计遇到重复流水号，跳过: projectId={}, requestNo={}",
                    projectId, requestNo);
            return;
        }

        RepairProject project = projectMapper.selectById(projectId);
        String projectNo = project == null ? String.valueOf(projectId) : project.getProjectNo();
        Long relicId = project == null ? null : project.getRelicId();
        String summary = "操作「" + operationName(operation) + "」并发冲突被拒绝"
                + (operatorName == null ? "" : "，发起操作人：" + operatorName)
                + "，拒绝原因：" + reason
                + (effective == null ? "，当前尚无生效尝试"
                : "；最终生效为尝试#" + effective.getId()
                        + (effective.getOperatorName() == null ? ""
                        : "（操作人：" + effective.getOperatorName() + "）"));
        ledgerService.append(relicId, projectId, null,
                ACTION_REPAIR_OP_REJECT, projectNo, summary, now);
    }

    private String operationName(String operation) {
        switch (operation) {
            case OP_START: return "开工";
            case OP_STAGE: return "阶段结果登记";
            case OP_FINISH: return "修复完成";
            case OP_ACCEPT: return "验收";
            case OP_CANCEL: return "作废";
            default: return operation;
        }
    }
}
