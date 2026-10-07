package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 已发布鉴定意见的追加更正：只追加，永不回写意见原文。
 * 更正序号在意见内从 1 递增，意见原文与全部更正共同构成该专家的完整立场。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_appraisal_correction")
public class AppraisalCorrection extends BaseEntity {

    private Long opinionId;

    /** 更正序号，意见内从 1 递增 */
    private Integer correctionNo;

    private String content;

    private String correctedBy;

    private LocalDateTime correctedAt;
}
