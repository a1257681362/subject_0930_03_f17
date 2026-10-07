package com.evops.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 虫害监测记录：一次现场提交一行，只追加。
 * 规则版本快照与判定结论在提交时固化——规则调整只生成新版本，不改写本表旧结论。
 * 监测结果按月份（monitorMonth）冻结：冻结后该月记录不再接受补录/更改。
 */
@Data
@TableName("t_pest_monitor_record")
public class PestMonitorRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String recordNo;

    private Long pointId;

    /** 提交时点位在布诱捕片（更换诱捕片后旧记录的 cardId 仍指向旧片，不丢历史） */
    private Long cardId;

    /** 提交时生效的规则版本快照 */
    private Long ruleVersionId;
    private String ruleVersionNo;

    /** 监测月份 yyyy-MM，按月份冻结锚点 */
    private String monitorMonth;

    /** NORMAL 正常 / OVER_THRESHOLD 超阈值 */
    private String conclusion;

    /** 超阈值虫种（、分隔），正常为 null */
    private String overSpecies;

    private String submittedBy;

    /** INTERNAL 馆内人员 / OUTSOURCE 外包人员（外包只能提交现场数据，不能关闭任务） */
    private String submitterRole;

    private LocalDateTime submittedAt;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
