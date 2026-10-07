package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 借阅补偿（museum-10）：修复部分成功导致保护等级调整时，与保护等级补偿
 * 成对开立、互相持有对方 id，确保两侧处置一一对应、联动闭环。
 * actionType: RECALL 在借/待出库提前收回（阻断出库，归还即闭环）
 *           / SUPPLEMENT_CONFIRM 已归还借阅的等级与保险差异补充确认。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_borrow_compensation")
public class BorrowCompensation extends BaseEntity {

    private String compensationNo;

    private Long applicationId;

    private Long relicId;

    private Long snapshotId;

    /** 互指的保护等级补偿 id */
    private Long gradeCompensationId;

    private String gradeBefore;

    private String gradeAfter;

    /** RECALL / SUPPLEMENT_CONFIRM */
    private String actionType;

    /** 等级调整前后的保险金额快照（跨馆区读取脱敏） */
    private BigDecimal insuranceBefore;

    private BigDecimal insuranceAfter;

    /** OPEN/RESOLVED */
    private String status;

    private LocalDateTime openedAt;

    private String openedBy;

    private String resolvedBy;

    private LocalDateTime resolvedAt;

    private String resolveNote;
}
