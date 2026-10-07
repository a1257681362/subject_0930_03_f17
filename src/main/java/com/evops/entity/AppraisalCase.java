package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 文物鉴定案件：串联初鉴与复鉴。
 * OPEN 待初鉴 -> REVIEWING 待复鉴 -> CONCLUDED 已定稿；
 * 初鉴/复鉴结论冲突时进入 ARBITRATING 仲裁，仲裁定稿后才 CONCLUDED。
 * 结论冲突绝不以最后一条意见覆盖前文，最终结论以案件头快照为准。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_appraisal_case")
public class AppraisalCase extends BaseEntity {

    /** 案件编号，全局唯一 */
    private String caseNo;

    private Long relicId;

    /** 关联修复项目（可空，鉴定可独立发起） */
    private Long projectId;

    private String title;

    /** OPEN / REVIEWING / ARBITRATING / CONCLUDED */
    private String status;

    /** 对外（普通馆员）可见的结论摘要，不含依据/置信说明/附件等明细 */
    private String conclusionSummary;

    /** INITIAL / REVIEW / ARBITRATION：最终结论落在哪一环 */
    private String finalStage;

    private Long finalOpinionId;

    private String finalBy;

    private LocalDateTime finalAt;
}
