package com.evops.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 详细鉴定报告下载审计流水：只追加、不修改、不删除。
 * 无论放行（GRANTED）还是权限不足拒绝（DENIED）都逐条留痕。
 */
@Data
@TableName("t_appraisal_report_audit")
public class AppraisalReportAudit {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long caseId;

    private String operatorName;

    private String operatorRole;

    /** GRANTED / DENIED */
    private String result;

    private String reason;

    private String clientIp;

    private LocalDateTime downloadedAt;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
