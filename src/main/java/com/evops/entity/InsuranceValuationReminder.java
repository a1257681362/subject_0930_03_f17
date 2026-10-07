package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 估值过期提醒（museum-15）：ACTIVE 估值有效期过后由定时扫描生成，一版本一条。
 *
 * <p>提醒只提示、须人工确认（OPEN -> ACKNOWLEDGED），<b>不改变任何业务状态、
 * 不冻结借展归还</b>：在借文物估值过期时归还验收照常进行，借展口径仍以
 * 签约快照为准。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_ins_valuation_reminder")
public class InsuranceValuationReminder extends BaseEntity {

    /** 提醒单号，全库唯一 */
    private String reminderNo;

    private Long relicId;

    private Long valuationVersionId;

    private String valuationNo;

    /** 估值有效期止 */
    private LocalDateTime expiredAt;

    private LocalDateTime remindedAt;

    /** OPEN 待处理 / ACKNOWLEDGED 已确认 */
    private String status;

    private String acknowledgedBy;

    private LocalDateTime acknowledgedAt;

    private String ackNote;
}
