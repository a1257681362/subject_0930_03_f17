package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.dto.AcceptRequest;
import com.evops.dto.CancelRequest;
import com.evops.dto.StageRequest;
import com.evops.entity.RepairAcceptance;
import com.evops.entity.RepairOperationAttempt;
import com.evops.entity.RepairProject;
import com.evops.entity.RepairStage;
import com.evops.mapper.RepairAcceptanceMapper;
import com.evops.mapper.RepairOperationAttemptMapper;
import com.evops.mapper.RepairProjectMapper;
import com.evops.mapper.RepairStageMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static com.evops.constant.MuseumConstants.*;

/**
 * 修复同一操作并发的统一入口。
 *
 * <p>同一项目的同一操作（开工/阶段登记/完成/验收/作废）并发发起时：</p>
 * <ol>
 *   <li>对项目行加排他行锁，把并发请求串行化；</li>
 *   <li>锁内先凭请求流水号幂等回读（补传/重试/补录命中同一次尝试，不重复执行）；</li>
 *   <li>状态仍允许则执行业务，并在<b>同一事务</b>写一条 EFFECTIVE 尝试，
 *       与项目状态推进原子提交；</li>
 *   <li>状态已不允许（说明并发对端已先行生效）则业务事务整体回滚，
 *       在独立事务补写一条 REJECTED 尝试并指向最终生效尝试，
 *       同时落 REPAIR_OP_REJECT 台账——谁生效、谁被拒绝逐条可溯。</li>
 * </ol>
 *
 * <p>原有 {@link RepairService} 的业务规则（会签、外借、阶段数、验收补偿等）
 * 全部保留，本类只负责并发串行化与尝试审计。</p>
 */
@Service
public class RepairOperationService {

    private final RepairProjectMapper projectMapper;
    private final RepairStageMapper stageMapper;
    private final RepairAcceptanceMapper acceptanceMapper;
    private final RepairOperationAttemptMapper attemptMapper;
    private final RepairService repairService;
    private final RepairAttemptAuditService auditService;
    private final TransactionTemplate transactionTemplate;

    public RepairOperationService(RepairProjectMapper projectMapper,
                                  RepairStageMapper stageMapper,
                                  RepairAcceptanceMapper acceptanceMapper,
                                  RepairOperationAttemptMapper attemptMapper,
                                  RepairService repairService,
                                  RepairAttemptAuditService auditService,
                                  PlatformTransactionManager transactionManager) {
        this.projectMapper = projectMapper;
        this.stageMapper = stageMapper;
        this.acceptanceMapper = acceptanceMapper;
        this.attemptMapper = attemptMapper;
        this.repairService = repairService;
        this.auditService = auditService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** 并发安全的开工 */
    public RepairProject start(Long projectId, String requestNo, String operatorName) {
        return run(projectId, OP_START, requestNo, operatorName,
                () -> repairService.startRepair(projectId));
    }

    /** 并发安全的阶段结果登记 */
    public RepairStage recordStage(Long projectId, StageRequest req) {
        return run(projectId, OP_STAGE, req.getRequestNo(), req.getOperator(),
                () -> repairService.recordStage(projectId, req));
    }

    /** 并发安全的修复完成 */
    public RepairProject finish(Long projectId, String requestNo, String operatorName) {
        return run(projectId, OP_FINISH, requestNo, operatorName,
                () -> repairService.finishRepair(projectId));
    }

    /** 并发安全的验收 */
    public RepairAcceptance accept(Long projectId, AcceptRequest req) {
        return run(projectId, OP_ACCEPT, req.getRequestNo(), req.getAcceptedBy(),
                () -> repairService.accept(projectId, req));
    }

    /** 并发安全的作废 */
    public RepairProject cancel(Long projectId, CancelRequest req) {
        return run(projectId, OP_CANCEL, req.getRequestNo(), req.getOperatorName(),
                () -> repairService.cancel(projectId, req.getReason()));
    }

    /** 并发尝试审计查询：谁生效、谁被拒绝 */
    public List<RepairOperationAttempt> listAttempts(Long projectId, String operation) {
        return attemptMapper.selectList(new LambdaQueryWrapper<RepairOperationAttempt>()
                .eq(RepairOperationAttempt::getProjectId, projectId)
                .eq(operation != null && !operation.isEmpty(),
                        RepairOperationAttempt::getOperation, operation)
                .orderByAsc(RepairOperationAttempt::getId));
    }

    // ---- 内部 ----

    @FunctionalInterface
    private interface BizAction<T> {
        T execute();
    }

    /**
     * 串行化执行一次修复操作。
     */
    private <T> T run(Long projectId, String operation, String requestNo, String operatorName,
                      BizAction<T> action) {
        // 快路径：锁前先看流水号，命中直接回放，避免无谓串行
        RepairOperationAttempt fast = findByRequestNo(requestNo);
        if (fast != null) {
            return replay(fast);
        }

        Outcome<T> outcome;
        try {
            @SuppressWarnings("unchecked")
            Outcome<T> executed = (Outcome<T>) transactionTemplate.execute(status -> {
                // 项目行锁：同项目并发操作在此排队
                projectMapper.lockRow(projectId);

                // 锁内二次回读：同流水号的并发补传在锁等待后命中同一次尝试
                RepairOperationAttempt raced = findByRequestNo(requestNo);
                if (raced != null) {
                    return Outcome.<T>replay(raced);
                }

                // 业务加入当前事务执行；状态/规则不允许（含并发落败）时异常直接透传，
                // TransactionTemplate 随之内滚——绝不捕获后强行提交
                // （内层 @Transactional 已将共享事务标记 rollback-only）。
                T biz = action.execute();

                LocalDateTime now = LocalDateTime.now();
                RepairOperationAttempt attempt = new RepairOperationAttempt();
                attempt.setProjectId(projectId);
                attempt.setOperation(operation);
                attempt.setRequestNo(requestNo);
                attempt.setOperatorName(operatorName);
                attempt.setDecision(ATTEMPT_EFFECTIVE);
                attempt.setBizId(extractBizId(biz));
                attempt.setOccurredAt(now);
                attemptMapper.insert(attempt);
                return Outcome.success(biz, attempt);
            });
            outcome = executed;
        } catch (IllegalArgumentException e) {
            // 业务事务已回滚：在独立事务补写 REJECTED 审计与台账后，原样抛出业务错误
            auditService.recordRejected(projectId, operation, requestNo,
                    operatorName, e.getMessage());
            throw e;
        }

        if (outcome == null) {
            throw new IllegalStateException("修复操作事务未返回结果");
        }
        if (outcome.kind == Outcome.Kind.REPLAY) {
            return replay(outcome.attempt);
        }
        return outcome.biz;
    }

    /** 幂等重放：生效的重放原业务结果；被拒的重放原拒绝原因 */
    private <T> T replay(RepairOperationAttempt attempt) {
        if (ATTEMPT_REJECTED.equals(attempt.getDecision())) {
            throw new IllegalArgumentException("该请求流水号此前已被拒绝："
                    + attempt.getRejectReason());
        }
        Object biz = reloadBiz(attempt);
        if (biz == null) {
            throw new IllegalArgumentException("该请求流水号对应的业务结果已不存在，无法重放");
        }
        @SuppressWarnings("unchecked")
        T typed = (T) biz;
        return typed;
    }

    private Object reloadBiz(RepairOperationAttempt attempt) {
        switch (attempt.getOperation()) {
            case OP_STAGE:
                return attempt.getBizId() == null ? null
                        : stageMapper.selectById(attempt.getBizId());
            case OP_ACCEPT:
                return attempt.getBizId() == null ? null
                        : acceptanceMapper.selectById(attempt.getBizId());
            case OP_START:
            case OP_FINISH:
            case OP_CANCEL:
            default:
                return projectMapper.selectById(attempt.getProjectId());
        }
    }

    private Long extractBizId(Object biz) {
        if (biz instanceof RepairStage) {
            return ((RepairStage) biz).getId();
        }
        if (biz instanceof RepairAcceptance) {
            return ((RepairAcceptance) biz).getId();
        }
        return null;
    }

    private RepairOperationAttempt findByRequestNo(String requestNo) {
        if (requestNo == null || requestNo.isEmpty()) {
            return null;
        }
        return attemptMapper.selectOne(new LambdaQueryWrapper<RepairOperationAttempt>()
                .eq(RepairOperationAttempt::getRequestNo, requestNo)
                .last("LIMIT 1"));
    }

    /** 事务内回传：成功 / 幂等重放 */
    private static final class Outcome<T> {
        enum Kind { SUCCESS, REPLAY }

        final Kind kind;
        final T biz;
        final RepairOperationAttempt attempt;

        private Outcome(Kind kind, T biz, RepairOperationAttempt attempt) {
            this.kind = kind;
            this.biz = biz;
            this.attempt = attempt;
        }

        static <T> Outcome<T> success(T biz, RepairOperationAttempt attempt) {
            return new Outcome<>(Kind.SUCCESS, biz, attempt);
        }

        static <T> Outcome<T> replay(RepairOperationAttempt attempt) {
            return new Outcome<>(Kind.REPLAY, null, attempt);
        }
    }
}
