package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.dto.SupplementCreateRequest;
import com.evops.entity.OpenDisplayGrant;
import com.evops.entity.RepairProject;
import com.evops.entity.RepairSupplement;
import com.evops.mapper.RepairProjectMapper;
import com.evops.mapper.RepairSupplementMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static com.evops.constant.MuseumConstants.*;

/**
 * 补修单：开放展示后发现问题的处置单。
 * 开立要求项目存在生效中的开放展示标记；开立不触碰原开放记录；
 * 闭环只置 CLOSED 留痕，记录保留。
 */
@Service
public class RepairSupplementService {

    private final RepairSupplementMapper supplementMapper;
    private final RepairProjectMapper projectMapper;
    private final OpenDisplayService openDisplayService;
    private final LedgerService ledgerService;

    public RepairSupplementService(RepairSupplementMapper supplementMapper,
                                   RepairProjectMapper projectMapper,
                                   OpenDisplayService openDisplayService,
                                   LedgerService ledgerService) {
        this.supplementMapper = supplementMapper;
        this.projectMapper = projectMapper;
        this.openDisplayService = openDisplayService;
        this.ledgerService = ledgerService;
    }

    /** 开立补修单：须有生效中的开放展示标记；原开放记录保留不动 */
    @Transactional
    public RepairSupplement create(Long projectId, SupplementCreateRequest req) {
        RepairProject project = requireProject(projectId);
        OpenDisplayGrant grant = openDisplayService.activeGrantOfProject(projectId);
        if (grant == null) {
            throw new IllegalArgumentException("项目无生效中的开放展示标记，不能开立补修单: "
                    + project.getProjectNo());
        }

        LocalDateTime now = LocalDateTime.now();
        RepairSupplement supplement = new RepairSupplement();
        supplement.setOrderNo(String.format("SP-%06d", supplementMapper.nextSupplementSeq()));
        supplement.setProjectId(projectId);
        supplement.setRelicId(project.getRelicId());
        supplement.setGrantId(grant.getId());
        supplement.setIssue(req.getIssue().trim());
        supplement.setStatus(SUPP_OPEN);
        supplement.setCreatedBy(req.getCreatedBy().trim());
        supplement.setCreatedAt(now);
        supplementMapper.insert(supplement);

        ledgerService.append(project.getRelicId(), projectId, null,
                ACTION_SUPPLEMENT_OPEN, supplement.getOrderNo(),
                "开放后发现问题，开立补修单（原开放记录保留，标记ID " + grant.getId()
                        + "）。问题：" + supplement.getIssue() + "，登记人：" + supplement.getCreatedBy(),
                now);
        return supplement;
    }

    /** 闭环补修单：登记处置结果，记录保留 */
    @Transactional
    public RepairSupplement close(Long supplementId, String closedBy, String closeNote) {
        RepairSupplement supplement = requireSupplement(supplementId);
        if (!SUPP_OPEN.equals(supplement.getStatus())) {
            throw new IllegalArgumentException("补修单已闭环，不能重复闭环: " + supplement.getOrderNo());
        }
        LocalDateTime now = LocalDateTime.now();
        supplement.setStatus(SUPP_CLOSED);
        supplement.setClosedBy(closedBy);
        supplement.setClosedAt(now);
        supplement.setCloseNote(closeNote);
        supplementMapper.updateById(supplement);

        ledgerService.append(supplement.getRelicId(), supplement.getProjectId(), null,
                ACTION_SUPPLEMENT_CLOSE, supplement.getOrderNo(),
                "补修单闭环。处置结果：" + closeNote + "，闭环人：" + closedBy, now);
        return supplement;
    }

    /** 项目补修单列表（含已闭环，管理端口径） */
    public List<RepairSupplement> listByProject(Long projectId) {
        requireProject(projectId);
        return supplementMapper.selectList(new LambdaQueryWrapper<RepairSupplement>()
                .eq(RepairSupplement::getProjectId, projectId)
                .orderByAsc(RepairSupplement::getId));
    }

    public RepairSupplement requireSupplement(Long supplementId) {
        RepairSupplement supplement = supplementMapper.selectById(supplementId);
        if (supplement == null) {
            throw new IllegalArgumentException("补修单不存在: id=" + supplementId);
        }
        return supplement;
    }

    private RepairProject requireProject(Long projectId) {
        RepairProject project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new IllegalArgumentException("修复项目不存在: id=" + projectId);
        }
        return project;
    }
}
