package com.evops.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 虫害台账：只追加、不修改、不删除。
 * 规则发布/点位建档迁移/诱捕片布放更换/监测提交/任务生成关闭/月份冻结
 * 全部留痕，尽量标明规则版本——虫害监测全程可追溯。
 */
@Data
@TableName("t_pest_ledger")
public class PestLedger {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long pointId;

    private Long recordId;

    private Long taskId;

    private Long ruleVersionId;

    private String ruleVersionNo;

    /** RULE_PUBLISH / POINT_CREATE / POINT_MOVE / POINT_RETIRE / CARD_DEPLOY
     *  / CARD_REPLACE / RECORD_SUBMIT / TASK_GENERATE / TASK_CLOSE / MONTH_FREEZE */
    private String action;

    private String bizNo;

    private String summary;

    private LocalDateTime occurredAt;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
