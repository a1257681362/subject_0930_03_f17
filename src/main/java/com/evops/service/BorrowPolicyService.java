package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.dto.BorrowPolicyUpdateRequest;
import com.evops.entity.BorrowGradePolicy;
import com.evops.mapper.BorrowGradePolicyMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.evops.constant.MuseumConstants.*;

/**
 * 按保护等级的借阅策略（museum-10）：
 * 每个等级一行当前值——可借阅场景、最高借阅时长（自然日）、有序审批角色、
 * 高风险是否必须追加馆长签名。
 *
 * <p>策略为当前值维护：已提交的借阅申请在提交时固化角色/时长/馆长要求，
 * 之后策略调整不影响在途审批与借阅快照。</p>
 */
@Service
public class BorrowPolicyService {

    private static final Set<String> SCENES = new LinkedHashSet<>(Arrays.asList(
            BORROW_SCENE_EXHIBITION, BORROW_SCENE_EXCHANGE,
            BORROW_SCENE_RESEARCH, BORROW_SCENE_EDUCATION));

    private static final Set<String> ROLES = new LinkedHashSet<>(Arrays.asList(
            BORROW_ROLE_DEPT, BORROW_ROLE_LEADER, BORROW_ROLE_DIRECTOR));

    private final BorrowGradePolicyMapper policyMapper;

    public BorrowPolicyService(BorrowGradePolicyMapper policyMapper) {
        this.policyMapper = policyMapper;
    }

    /** 取某等级策略；未配置（理论上 schema 已播种）直接拒绝业务 */
    public BorrowGradePolicy requirePolicy(String grade) {
        BorrowGradePolicy policy = policyMapper.selectOne(new LambdaQueryWrapper<BorrowGradePolicy>()
                .eq(BorrowGradePolicy::getPreservationGrade, grade));
        if (policy == null) {
            throw new IllegalArgumentException("该保护等级尚未配置借阅策略: " + grade);
        }
        return policy;
    }

    public List<BorrowGradePolicy> listPolicies() {
        return policyMapper.selectList(new LambdaQueryWrapper<BorrowGradePolicy>()
                .orderByAsc(BorrowGradePolicy::getId));
    }

    /** 更新某等级的借阅策略当前值；已提交申请的固化快照不受影响 */
    @Transactional
    public BorrowGradePolicy updatePolicy(String grade, BorrowPolicyUpdateRequest req) {
        if (!GRADE_ONE.equals(grade) && !GRADE_TWO.equals(grade) && !GRADE_THREE.equals(grade)) {
            throw new IllegalArgumentException("保护等级只能为 ONE/TWO/THREE");
        }
        List<String> scenes = normalizeCodes(req.getAllowedScenes(), SCENES, "可借阅场景");
        List<String> roles = normalizeCodes(req.getApprovalRoles(), ROLES, "审批角色");

        BorrowGradePolicy policy = requirePolicy(grade);
        policy.setAllowedScenes(String.join(",", scenes));
        policy.setMaxDurationDays(req.getMaxDurationDays());
        policy.setApprovalRoles(String.join(",", roles));
        policy.setDirectorSignRequired(Boolean.TRUE.equals(req.getDirectorSignRequired()) ? 1 : 0);
        policyMapper.updateById(policy);
        return policy;
    }

    /** 场景是否在策略允许范围内 */
    public boolean sceneAllowed(BorrowGradePolicy policy, String scene) {
        return splitCsv(policy.getAllowedScenes()).contains(scene);
    }

    /** 有序审批角色 */
    public List<String> rolesOf(BorrowGradePolicy policy) {
        return splitCsv(policy.getApprovalRoles());
    }

    private List<String> normalizeCodes(List<String> input, Set<String> allowed, String label) {
        List<String> normalized = new ArrayList<>();
        Set<String> dedup = new LinkedHashSet<>();
        for (String code : input) {
            String trimmed = code == null ? "" : code.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!allowed.contains(trimmed)) {
                throw new IllegalArgumentException(label + "包含未知码值: " + trimmed);
            }
            if (!dedup.add(trimmed)) {
                throw new IllegalArgumentException(label + "不能重复配置: " + trimmed);
            }
            normalized.add(trimmed);
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(label + "至少配置一个");
        }
        return normalized;
    }

    private List<String> splitCsv(String csv) {
        List<String> result = new ArrayList<>();
        if (csv == null) {
            return result;
        }
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }
}
