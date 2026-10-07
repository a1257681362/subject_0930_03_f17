package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 修复方案版本：每次提交一个新版本，版本号项目内递增。
 * 任一专家退回即封存 RETURNED 只读，修订只能另开新版本；
 * 全部会签通过后 APPROVED，才允许开工生成施工任务。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_repair_plan_version")
public class RepairPlanVersion extends BaseEntity {

    private Long projectId;

    /** 版本号，项目内从 1 递增 */
    private Integer versionNo;

    /** 方案内容 */
    private String planContent;

    /** 提交时文物保存等级快照（ONE/TWO/THREE），决定会签顺序 */
    private String riskLevel;

    /** SIGNING / RETURNED / APPROVED */
    private String status;

    /** 指定应签人：馆内专家 */
    private String internalExpert;

    /** 指定应签人：外部专家 */
    private String externalExpert;

    /** 指定应签人：保管人员 */
    private String custodian;

    private String submittedBy;

    private LocalDateTime submittedAt;

    /** 退回或通过的时间；SIGNING 时为空 */
    private LocalDateTime closedAt;
}
