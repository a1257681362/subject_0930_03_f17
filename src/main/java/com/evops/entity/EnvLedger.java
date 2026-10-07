package com.evops.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 环境巡检台账：只追加、不修改、不删除。
 * 规则发布、任务生成、读数、事件开/并/恢复/处置/复核关闭全部留痕，
 * 每条尽量带规则版本号，环境指标与巡检计划据此全程可追溯。
 */
@Data
@TableName("t_env_ledger")
public class EnvLedger {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long hallId;

    private Long taskId;

    private Long eventId;

    private Long ruleVersionId;

    private String ruleVersionNo;

    /** RULE_PUBLISH / TASK_GENERATE / READING_OK / READING_ABNORMAL
     *  / EVENT_OPEN / EVENT_MERGE / EVENT_RESOLVE / EVENT_HANDLE
     *  / EVENT_REVIEW_REJECT / EVENT_CLOSE */
    private String action;

    private String bizNo;

    private String summary;

    private LocalDateTime occurredAt;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
