package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 鉴定意见：初鉴（INITIAL）与复鉴（REVIEW）分别成行，轮次项目内递增。
 * 每条意见独立固化专家、依据、置信说明与附件，谁也不覆盖谁。
 * DRAFT 未发布仅专家本人可见且可撤回（置 WITHDRAWN 留痕，不删行）；
 * PUBLISHED 已发布原文永不改写，修订只能追加 t_appraisal_correction。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_appraisal_opinion")
public class AppraisalOpinion extends BaseEntity {

    private Long caseId;

    /** INITIAL 初鉴 / REVIEW 复鉴 */
    private String stage;

    /** 轮次，同一案件同一阶段从 1 递增（撤回后重提即下一轮） */
    private Integer roundNo;

    private String expertName;

    private String expertTitle;

    /** 鉴定依据（文献/检材/工艺比对等） */
    private String basis;

    /** 置信说明：把握程度、不确定性与边界条件 */
    private String confidenceNote;

    /** AUTHENTIC / COUNTERFEIT / PARTIAL / INCONCLUSIVE */
    private String conclusionCode;

    private String conclusionText;

    /** DRAFT / PUBLISHED / WITHDRAWN */
    private String status;

    private String submittedBy;

    private LocalDateTime submittedAt;

    private LocalDateTime publishedAt;

    private LocalDateTime withdrawnAt;

    private String withdrawReason;
}
