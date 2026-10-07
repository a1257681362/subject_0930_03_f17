package com.evops.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 包装台账：只追加。规格发布/装箱/替代审批/外部通知/拆箱全部留痕，尽量标明规格版本。
 */
@Data
@TableName("t_pack_ledger")
public class PackLedger {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long relicId;
    private Long orderId;
    private Long substituteId;
    private Long specVersionId;
    private String specVersionNo;
    private String action;
    private String bizNo;
    private String summary;
    private LocalDateTime occurredAt;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
