package com.evops.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 清点台账：只追加。任务生成/扫描/重基线回放/差异单开立与复核/
 * 任务关闭/补充记录/展厅关闭全部留痕。
 */
@Data
@TableName("t_inv_ledger")
public class InvLedger {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long hallId;

    private Long taskId;

    private Long diffId;

    private String action;

    private String bizNo;

    private String summary;

    private LocalDateTime occurredAt;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
