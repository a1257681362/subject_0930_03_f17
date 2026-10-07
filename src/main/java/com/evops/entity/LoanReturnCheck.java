package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 归还验收头：一个协议一条。
 * ALL_MATCH 全部相符；任一行损伤/缺失即为 WITH_DIFF，差异在明细行永久保留。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_loan_return_check")
public class LoanReturnCheck extends BaseEntity {

    private Long agreementId;

    /** 验收单号 */
    private String checkNo;

    private String checkedBy;

    private LocalDateTime checkedAt;

    /** ALL_MATCH / WITH_DIFF */
    private String conclusion;

    private String summary;
}
