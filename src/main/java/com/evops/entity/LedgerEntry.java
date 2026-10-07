package com.evops.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 台账流水：入藏/借出/归还/修复/验收/撤销/作废的不可变动作留痕。
 * 只追加、不修改、不删除。
 */
@Data
@TableName("t_relic_ledger")
public class LedgerEntry {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long relicId;

    private Long projectId;

    private Long loanId;

    /** INTAKE / LOAN_OUT / RETURN / REPAIR_START / REPAIR_STAGE /
     *  REPAIR_FINISH / ACCEPT / REVOKE / CANCEL */
    private String action;

    /** 关联业务单号，便于追溯 */
    private String bizNo;

    private String summary;

    private LocalDateTime occurredAt;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
