package com.evops.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 年度报表台账流水：口径发布/生成/撤回/重提/异常凭证/任务失败/告警确认
 * 全部只追加留痕。只有 create_* 列，不修改、不删除。
 */
@Data
@TableName("t_annual_report_ledger")
public class AnnualReportLedger {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long reportId;

    private Long caliberVersionId;

    private String action;

    private String bizNo;

    private String summary;

    private LocalDateTime occurredAt;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
