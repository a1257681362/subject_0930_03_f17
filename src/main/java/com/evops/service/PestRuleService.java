package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.dto.PestRulePublishRequest;
import com.evops.entity.PestRuleItem;
import com.evops.entity.PestRuleVersion;
import com.evops.mapper.PestRuleItemMapper;
import com.evops.mapper.PestRuleVersionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.evops.constant.MuseumConstants.*;

/**
 * 虫害阈值规则版本：
 * 规则调整只能发布新版本（各虫种阈值数量），旧 ACTIVE 版本置 SUPERSEDED 只读，
 * 版本与条目只追加不改写；已提交的监测记录固化版本快照与结论，不受新版本影响。
 */
@Service
public class PestRuleService {

    private final PestRuleVersionMapper versionMapper;
    private final PestRuleItemMapper itemMapper;
    private final PestLedgerService ledgerService;

    public PestRuleService(PestRuleVersionMapper versionMapper,
                           PestRuleItemMapper itemMapper,
                           PestLedgerService ledgerService) {
        this.versionMapper = versionMapper;
        this.itemMapper = itemMapper;
        this.ledgerService = ledgerService;
    }

    /**
     * 发布规则新版本：同事务内将旧生效版本置 SUPERSEDED。
     * 版本号全库唯一；同版本内虫种不可重复。
     */
    @Transactional
    public PestRuleVersion publish(PestRulePublishRequest req) {
        if (versionMapper.selectCount(new LambdaQueryWrapper<PestRuleVersion>()
                .eq(PestRuleVersion::getVersionNo, req.getVersionNo())) > 0) {
            throw new IllegalArgumentException("规则版本号已存在: " + req.getVersionNo());
        }
        Set<String> species = new HashSet<>();
        for (PestRulePublishRequest.Item it : req.getItems()) {
            if (!species.add(it.getPestSpecies().trim())) {
                throw new IllegalArgumentException("同一规则版本内虫种不可重复: " + it.getPestSpecies());
            }
        }

        LocalDateTime now = LocalDateTime.now();
        PestRuleVersion oldActive = activeVersionEntity();
        if (oldActive != null) {
            // 旧版本只置状态、内容永不改写；引用旧版本的监测记录结论不变
            oldActive.setStatus(PEST_RULE_SUPERSEDED);
            versionMapper.updateById(oldActive);
        }

        PestRuleVersion version = new PestRuleVersion();
        version.setVersionNo(req.getVersionNo());
        version.setTitle(req.getTitle());
        version.setRemark(req.getRemark());
        version.setStatus(PEST_RULE_ACTIVE);
        version.setPublishedBy(req.getPublishedBy());
        version.setPublishedAt(now);
        versionMapper.insert(version);

        for (PestRulePublishRequest.Item it : req.getItems()) {
            PestRuleItem item = new PestRuleItem();
            item.setRuleVersionId(version.getId());
            item.setPestSpecies(it.getPestSpecies().trim());
            item.setThresholdCount(it.getThresholdCount());
            itemMapper.insert(item);
        }

        ledgerService.append(null, null, null, version.getId(), version.getVersionNo(),
                ACTION_PEST_RULE_PUBLISH, version.getVersionNo(),
                "虫害阈值规则【" + version.getTitle() + "】" + version.getVersionNo()
                        + " 发布生效，含 " + req.getItems().size() + " 个虫种条目"
                        + (oldActive != null ? "；旧版本 " + oldActive.getVersionNo()
                        + " 置 SUPERSEDED 只读，监测记录旧结论不改写" : ""),
                now);
        return version;
    }

    public PestRuleVersion requireActiveVersion() {
        PestRuleVersion version = activeVersionEntity();
        if (version == null) {
            throw new IllegalArgumentException("尚未发布生效的虫害阈值规则");
        }
        return version;
    }

    public PestRuleVersion activeVersionEntity() {
        return versionMapper.selectOne(new LambdaQueryWrapper<PestRuleVersion>()
                .eq(PestRuleVersion::getStatus, PEST_RULE_ACTIVE)
                .last("LIMIT 1"));
    }

    public PestRuleVersion requireVersion(Long versionId) {
        PestRuleVersion version = versionMapper.selectById(versionId);
        if (version == null) {
            throw new IllegalArgumentException("规则版本不存在: id=" + versionId);
        }
        return version;
    }

    public List<PestRuleVersion> listVersions() {
        return versionMapper.selectList(new LambdaQueryWrapper<PestRuleVersion>()
                .orderByDesc(PestRuleVersion::getId));
    }

    public List<PestRuleItem> listItems(Long ruleVersionId) {
        return itemMapper.selectList(new LambdaQueryWrapper<PestRuleItem>()
                .eq(PestRuleItem::getRuleVersionId, ruleVersionId)
                .orderByAsc(PestRuleItem::getPestSpecies));
    }
}
