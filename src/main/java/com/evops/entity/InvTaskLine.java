package com.evops.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 清点基线快照行：按版本只追加，旧版本永不改写。
 * 生成/重基线时把展厅在展文物的展签、箱号、点位与在借单 id 快照入行。
 */
@Data
@TableName("t_inv_task_line")
public class InvTaskLine {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    private Integer baselineVersion;

    private Long relicId;

    /** 文物编号快照 */
    private String relicNo;

    /** 建档材质快照 */
    private String material;

    /** 点位（展柜/区域）快照 */
    private String pointCode;

    /** 展签（陈展编号）快照 */
    private String displayNo;

    /** 箱号快照 */
    private String boxNo;

    /** 基线时该文物在借单 id（无则 NULL）：借还变更后与快照不一致即版本冲突 */
    private Long baselineLoanId;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
