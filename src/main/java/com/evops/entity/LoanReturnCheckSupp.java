package com.evops.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 归还验收差异补录：验收闭环后复查发现的差异只追加，不改写原比对行。
 * 补录不重新打开已完成归还——协议保持 RETURNED、贯通借出单保持 RETURNED；
 * 首条补录把验收结论 ALL_MATCH 更正为 WITH_DIFF。
 */
@Data
@TableName("t_loan_return_check_supp")
public class LoanReturnCheckSupp {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long checkId;

    /** 原借展清单行 id（t_loan_manifest_line.id） */
    private Long manifestLineId;

    private String itemNo;

    private String itemName;

    private Integer qty;

    /** 补录差异类型：DAMAGED 损伤 / MISSING 缺失 */
    private String result;

    /** 差异说明（补录必填） */
    private String diffNote;

    private String supplementedBy;

    private LocalDateTime supplementedAt;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
