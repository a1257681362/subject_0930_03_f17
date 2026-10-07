package com.evops.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 归还验收逐行比对：引用原借展清单行并固化条目快照，只追加。
 * result: INTACT 相符 / DAMAGED 损伤 / MISSING 缺失；非 INTACT 必填差异说明。
 */
@Data
@TableName("t_loan_return_check_line")
public class LoanReturnCheckLine {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long checkId;

    /** 原借展清单行 id（t_loan_manifest_line.id） */
    private Long manifestLineId;

    private String itemNo;

    private String itemName;

    private Integer qty;

    /** INTACT / DAMAGED / MISSING */
    private String result;

    /** 差异说明（损伤/缺失时必填） */
    private String diffNote;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private java.time.LocalDateTime createTime;
}
