package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 环境巡检规则版本：只追加。
 * status: DRAFT 草稿 / ACTIVE 当前生效 / SUPERSEDED 已被新版本取代。
 * 发布新版本时旧 ACTIVE 置 SUPERSEDED，旧版本内容与引用它的任务/读数永不改写。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_env_rule_version")
public class EnvRuleVersion extends BaseEntity {

    private String versionNo;

    private String title;

    private String remark;

    /** DRAFT / ACTIVE / SUPERSEDED */
    private String status;

    private String publishedBy;

    private LocalDateTime publishedAt;
}
