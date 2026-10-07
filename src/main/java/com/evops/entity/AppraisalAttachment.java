package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 鉴定附件：意见 / 更正 / 仲裁三选一归属（CHECK 约束保证仅一处非空）。
 * 附件随归属对象固化，不随意见撤回而删除。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_appraisal_attachment")
public class AppraisalAttachment extends BaseEntity {

    private Long opinionId;

    private Long correctionId;

    private Long arbitrationId;

    private String fileName;

    private String filePath;

    /** 内容哈希（如 SHA-256），可选 */
    private String fileHash;

    /** OPINION / CORRECTION / ARBITRATION */
    private String bizType;

    private String uploadedBy;

    private LocalDateTime uploadedAt;
}
