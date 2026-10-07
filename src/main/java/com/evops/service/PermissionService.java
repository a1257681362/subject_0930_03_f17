package com.evops.service;

import com.evops.common.PermissionClosedException;
import com.evops.entity.PermissionPolicy;
import com.evops.mapper.PermissionPolicyMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static com.evops.constant.MuseumConstants.POLICY_ID;

/**
 * 文物修复权限策略：单行开关。
 *
 * <p>当博物馆文物修复权限被收紧（{@code repairPhotoUploadOpen=false}）时，
 * 影像上传等推进型动作立即停止；放开后恢复。策略翻转不回滚、不删除任何
 * 已确认的影像版本、物理文件或台账事实。</p>
 *
 * <p>{@code valuationChangeOpen=false} 收紧「文物保险估值变更留痕」：
 * 估值的一切变更动作在服务入口统一拒绝，换调用地址也不放行；
 * 已生效版本、保险批单、借展快照与台账事实继续保留。</p>
 */
@Service
public class PermissionService {

    private final PermissionPolicyMapper policyMapper;

    public PermissionService(PermissionPolicyMapper policyMapper) {
        this.policyMapper = policyMapper;
    }

    /** 当前是否允许影像上传 */
    public boolean isUploadOpen() {
        return getPolicy().getRepairPhotoUploadOpen();
    }

    /** 当前是否允许文物保险估值变更留痕 */
    public boolean isValuationChangeOpen() {
        return getPolicy().getValuationChangeOpen();
    }

    /** 收紧时直接拒绝，上传不推进任何写入 */
    public void requireUploadOpen() {
        if (!isUploadOpen()) {
            throw new PermissionClosedException(
                    "博物馆文物修复权限已收紧，影像上传已停止推进；已确认的版本归档继续保留");
        }
    }

    /**
     * 「文物保险估值变更留痕」入口闸门：收紧时所有估值推进型动作在此统一拒绝。
     * 该检查必须落在服务层入口而不是某个 URL 上——换调用地址同样命中本闸门，不能放行。
     */
    public void requireValuationChangeOpen() {
        if (!isValuationChangeOpen()) {
            throw new PermissionClosedException(
                    "博物馆文物修复用户缺权：文物保险估值变更留痕入口已收紧，"
                            + "估值登记/修订/审批/批单等变更一律在入口拒绝；"
                            + "已生效估值版本、保险批单与借展签约快照继续保留只读");
        }
    }

    public PermissionPolicy getPolicy() {
        PermissionPolicy policy = policyMapper.selectById(POLICY_ID);
        if (policy == null) {
            // 理论上 schema.sql 已播种 id=1；兜底补一条开放策略，避免空指针
            policy = new PermissionPolicy();
            policy.setId(POLICY_ID);
            policy.setRepairPhotoUploadOpen(true);
            policy.setValuationChangeOpen(true);
            policy.setUpdatedBy("SYSTEM");
            policy.setUpdatedAt(LocalDateTime.now());
            policyMapper.insert(policy);
        }
        // 旧库（museum-15 前建库）可能无新列值，按开放兜底，避免历史数据 NPE
        if (policy.getValuationChangeOpen() == null) {
            policy.setValuationChangeOpen(true);
        }
        return policy;
    }

    /** 收紧 / 放开影像上传，返回最新策略 */
    @Transactional
    public PermissionPolicy updateOpen(boolean open, String operator, String reason) {
        PermissionPolicy policy = getPolicy();
        boolean changed = !Boolean.valueOf(open).equals(policy.getRepairPhotoUploadOpen());
        policy.setRepairPhotoUploadOpen(open);
        policy.setUpdatedBy(operator == null || operator.isEmpty() ? "SYSTEM" : operator);
        policy.setUpdatedAt(LocalDateTime.now());
        policyMapper.updateById(policy);
        // reason 仅用于调用方留痕/日志，不另建历史表
        if (changed) {
            org.slf4j.LoggerFactory.getLogger(PermissionService.class)
                    .info("文物修复影像上传权限已{}，操作人={}，原因={}",
                            open ? "放开" : "收紧", policy.getUpdatedBy(), reason);
        }
        return policy;
    }

    /** 收紧 / 放开文物保险估值变更留痕入口，返回最新策略 */
    @Transactional
    public PermissionPolicy updateValuationChangeOpen(boolean open, String operator, String reason) {
        PermissionPolicy policy = getPolicy();
        boolean changed = !Boolean.valueOf(open).equals(policy.getValuationChangeOpen());
        policy.setValuationChangeOpen(open);
        policy.setUpdatedBy(operator == null || operator.isEmpty() ? "SYSTEM" : operator);
        policy.setUpdatedAt(LocalDateTime.now());
        policyMapper.updateById(policy);
        if (changed) {
            org.slf4j.LoggerFactory.getLogger(PermissionService.class)
                    .info("文物保险估值变更留痕入口已{}，操作人={}，原因={}",
                            open ? "放开" : "收紧", policy.getUpdatedBy(), reason);
        }
        return policy;
    }
}
