package com.evops.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 差异明细行：缺失 MISSING / 错位 MISPLACED / 状态异常 STATUS_ABNORMAL。
 * 逐行固化期望与实际（箱号/展签/点位），随差异单永久保留。
 */
@Data
@TableName("t_inv_diff_line")
public class InvDiffLine {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long diffId;

    /** MISSING / MISPLACED / STATUS_ABNORMAL */
    private String diffType;

    private Long relicId;

    private String relicNo;

    private String displayNo;

    private String boxNoExpected;

    private String boxNoActual;

    private String pointCode;

    /** 差异说明（如「文物在借未还却在场」「未在基线中的展签」） */
    private String detail;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
