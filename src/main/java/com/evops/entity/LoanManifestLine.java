package com.evops.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 借展清单行：随协议版本固化的出库清单快照，只追加。
 * 归还验收以出库签署版本的清单为「原借展清单」逐项比对；版本封存后快照永不改写。
 */
@Data
@TableName("t_loan_manifest_line")
public class LoanManifestLine {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long agreementVersionId;

    /** 行号，版本内从 1 递增 */
    private Integer lineNo;

    /** 条目编号（文物/附件/包装件编号） */
    private String itemNo;

    private String itemName;

    private Integer qty;

    /** 出库品相/备注 */
    private String outCondition;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private java.time.LocalDateTime createTime;
}
