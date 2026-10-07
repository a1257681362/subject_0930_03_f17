package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 仲裁单：初鉴与复鉴已发布结论冲突时自动开立（不覆盖任何一方）。
 * 仲裁人可采初鉴、采复鉴或综合调整；定稿后回写案件最终结论。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_appraisal_arbitration")
public class AppraisalArbitration extends BaseEntity {

    private String arbitrationNo;

    private Long caseId;

    private Long initialOpinionId;

    private Long reviewOpinionId;

    /** 冲突点说明：固化两轮结论码与分歧摘要 */
    private String conflictSummary;

    /** OPEN / CONCLUDED */
    private String status;

    private String arbiterName;

    /** ADOPT_INITIAL / ADOPT_REVIEW / ADJUSTED */
    private String finalDecision;

    private String finalConclusionCode;

    private String finalConclusionText;

    private String finalBasis;

    private String confidenceNote;

    private String openedBy;

    private LocalDateTime openedAt;

    private LocalDateTime concludedAt;
}
