package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.dto.RuleItemRequest;
import com.evops.dto.RulePublishRequest;
import com.evops.entity.EnvRuleItem;
import com.evops.entity.EnvRuleVersion;
import com.evops.mapper.EnvRuleItemMapper;
import com.evops.mapper.EnvRuleVersionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.evops.constant.MuseumConstants.*;

/**
 * 环境巡检规则版本：
 * 发布新版本（含各材质温湿度/照度阈值与采样频率）后，旧 ACTIVE 版本置 SUPERSEDED，
 * 版本与条目只追加不改写；已生成的任务、已提交的读数均固化版本快照，不受新版本影响。
 */
@Service
public class EnvRuleService {

    private final EnvRuleVersionMapper versionMapper;
    private final EnvRuleItemMapper itemMapper;
    private final EnvLedgerService ledgerService;

    public EnvRuleService(EnvRuleVersionMapper versionMapper,
                          EnvRuleItemMapper itemMapper,
                          EnvLedgerService ledgerService) {
        this.versionMapper = versionMapper;
        this.itemMapper = itemMapper;
        this.ledgerService = ledgerService;
    }

    /**
     * 发布规则新版本：同事务内将旧生效版本置 SUPERSEDED。
     * 版本号全库唯一；同版本内材质不可重复；阈值上下限自洽。
     */
    @Transactional
    public EnvRuleVersion publish(RulePublishRequest req) {
        if (versionMapper.selectCount(new LambdaQueryWrapper<EnvRuleVersion>()
                .eq(EnvRuleVersion::getVersionNo, req.getVersionNo())) > 0) {
            throw new IllegalArgumentException("规则版本号已存在: " + req.getVersionNo());
        }
        validateItems(req.getItems());

        LocalDateTime now = LocalDateTime.now();
        EnvRuleVersion oldActive = activeVersionEntity();
        if (oldActive != null) {
            // 旧版本只置状态、内容永不改写；引用旧版本的任务/读数仍指向旧版本
            oldActive.setStatus(ENV_RULE_SUPERSEDED);
            versionMapper.updateById(oldActive);
        }

        EnvRuleVersion version = new EnvRuleVersion();
        version.setVersionNo(req.getVersionNo());
        version.setTitle(req.getTitle());
        version.setRemark(req.getRemark());
        version.setStatus(ENV_RULE_ACTIVE);
        version.setPublishedBy(req.getPublishedBy());
        version.setPublishedAt(now);
        versionMapper.insert(version);

        for (RuleItemRequest it : req.getItems()) {
            EnvRuleItem item = new EnvRuleItem();
            item.setRuleVersionId(version.getId());
            item.setMaterial(it.getMaterial().trim());
            item.setTempMin(it.getTempMin());
            item.setTempMax(it.getTempMax());
            item.setHumidityMin(it.getHumidityMin());
            item.setHumidityMax(it.getHumidityMax());
            item.setLuxMin(it.getLuxMin());
            item.setLuxMax(it.getLuxMax());
            item.setSampleIntervalMin(it.getSampleIntervalMin());
            itemMapper.insert(item);
        }

        ledgerService.append(null, null, null, version.getId(), version.getVersionNo(),
                ACTION_RULE_PUBLISH, version.getVersionNo(),
                "环境巡检规则【" + version.getTitle() + "】" + version.getVersionNo()
                        + " 发布生效，含 " + req.getItems().size() + " 个材质条目"
                        + (oldActive != null ? "；旧版本 " + oldActive.getVersionNo() + " 置 SUPERSEDED 只读" : ""),
                now);
        return version;
    }

    private void validateItems(List<RuleItemRequest> items) {
        Set<String> materials = new HashSet<>();
        for (RuleItemRequest it : items) {
            String material = it.getMaterial().trim();
            if (!materials.add(material)) {
                throw new IllegalArgumentException("同一规则版本内材质不可重复: " + material);
            }
            if (it.getTempMin().compareTo(it.getTempMax()) > 0) {
                throw new IllegalArgumentException("材质【" + material + "】温度下限不能大于上限");
            }
            if (it.getHumidityMin().compareTo(it.getHumidityMax()) > 0) {
                throw new IllegalArgumentException("材质【" + material + "】湿度下限不能大于上限");
            }
            if (it.getLuxMin().compareTo(it.getLuxMax()) > 0) {
                throw new IllegalArgumentException("材质【" + material + "】照度下限不能大于上限");
            }
        }
    }

    public EnvRuleVersion requireActiveVersion() {
        EnvRuleVersion version = activeVersionEntity();
        if (version == null) {
            throw new IllegalArgumentException("尚未发布生效的环境巡检规则");
        }
        return version;
    }

    public EnvRuleVersion activeVersionEntity() {
        return versionMapper.selectOne(new LambdaQueryWrapper<EnvRuleVersion>()
                .eq(EnvRuleVersion::getStatus, ENV_RULE_ACTIVE)
                .last("LIMIT 1"));
    }

    public EnvRuleVersion requireVersion(Long versionId) {
        EnvRuleVersion version = versionMapper.selectById(versionId);
        if (version == null) {
            throw new IllegalArgumentException("规则版本不存在: id=" + versionId);
        }
        return version;
    }

    public List<EnvRuleVersion> listVersions() {
        return versionMapper.selectList(new LambdaQueryWrapper<EnvRuleVersion>()
                .orderByDesc(EnvRuleVersion::getId));
    }

    public List<EnvRuleItem> listItems(Long ruleVersionId) {
        return itemMapper.selectList(new LambdaQueryWrapper<EnvRuleItem>()
                .eq(EnvRuleItem::getRuleVersionId, ruleVersionId)
                .orderByAsc(EnvRuleItem::getMaterial));
    }
}
