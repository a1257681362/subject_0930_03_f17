package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 差异单：一任务一版本一单。差异必须能解释——说明与见证人必填，
 * 复核前至少一张现场照片；复核完成前不能关闭展览。
 * status: OPEN 待复核 / REVIEWED 复核完成。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_inv_diff_order")
public class InvDiffOrder extends BaseEntity {

    private String diffNo;

    private Long taskId;

    private Integer baselineVersion;

    /** 开立时差异汇总快照 */
    private Integer missingCount;
    private Integer misplacedCount;
    private Integer abnormalCount;

    /** 差异说明（差异必须能解释） */
    private String explanation;

    /** 见证人（至少一名；两名时不得同名） */
    private String witness1;
    private String witness2;

    /** OPEN / REVIEWED */
    private String status;

    private String reviewedBy;

    private LocalDateTime reviewedAt;

    private String reviewNote;

    private String createdBy;
}
