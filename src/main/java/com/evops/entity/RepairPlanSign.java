package com.evops.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会签记录：只追加。逐步记录应签角色、实签人、意见、签名时间与依据附件。
 * 签名永远归属"签署时处于会签中的方案版本"，版本封存后不再接受签名。
 */
@Data
@TableName("t_repair_plan_sign")
public class RepairPlanSign {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long planVersionId;

    /** 会签步骤号，版本内从 1 递增，与配置的会签顺序一一对应 */
    private Integer stepNo;

    /** 应签角色：INTERNAL 馆内专家 / EXTERNAL 外部专家 / CUSTODIAN 保管人员 */
    private String signerRole;

    /** 实签人，必须与版本指定的应签人一致（不允许代签） */
    private String signerName;

    /** APPROVE 同意 / RETURN 退回 */
    private String decision;

    /** 会签意见 */
    private String opinion;

    /** 依据附件（鉴定报告、检测数据等引用） */
    private String attachment;

    /** 签名时间 */
    private LocalDateTime signedAt;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
