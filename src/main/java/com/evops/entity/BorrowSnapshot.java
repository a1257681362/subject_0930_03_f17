package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 借阅快照（museum-10）：审批全部通过时生成，一件申请一份，生成后不可变。
 * 固化文物档案、借展方、场景/期限/保险、完整审批链与馆长签名；
 * 跨馆区查看时由服务层对保险金额脱敏。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_borrow_snapshot")
public class BorrowSnapshot extends BaseEntity {

    private Long applicationId;

    private Long relicId;

    private String relicNo;

    private String relicName;

    private String preservationGrade;

    /** 文物所属馆区，跨馆区读取保险金额时脱敏 */
    private String campusCode;

    private String category;

    private String material;

    private String era;

    private String borrower;

    private String borrowScene;

    private String purpose;

    private BigDecimal insuranceAmount;

    private LocalDateTime planLentAt;

    private LocalDateTime expectedReturnAt;

    private Integer durationDays;

    /** 有序审批角色快照 */
    private String approvalRoles;

    /** 完整审批链文本快照：步骤/角色/实签人/时间 */
    private String approvalChain;

    /** 是否含高风险馆长签名 1/0 */
    private Integer directorSigned;

    private String directorSignName;

    private LocalDateTime generatedAt;
}
