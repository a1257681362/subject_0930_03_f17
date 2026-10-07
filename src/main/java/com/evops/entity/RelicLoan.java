package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 借出/归还记录：一次借还一条。借出 LENT，归还后 RETURNED，不做物理删除。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_relic_loan")
public class RelicLoan extends BaseEntity {

    /** 借出单号 */
    private String loanNo;

    private Long relicId;

    /** 借用人/单位 */
    private String borrower;

    /** 借出用途 */
    private String purpose;

    private LocalDateTime lentAt;

    private LocalDateTime expectedReturnAt;

    private LocalDateTime returnedAt;

    /** 归还时品相 */
    private String returnCondition;

    /** 归还接收人 */
    private String receiver;

    /** LENT / RETURNED / OVERDUE */
    private String status;
}
