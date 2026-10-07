package com.evops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 借阅审批签署记录（museum-10）：只追加。
 * 原申请审批链 extensionId=0（哨兵值）；归还延期重新审批链挂延期单 id。
 * 同一链 (applicationId, extensionId, stepNo) 唯一。
 */
@Data
@TableName("t_borrow_approval_record")
public class BorrowApprovalRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long applicationId;

    /** 原申请链固定为 0；延期重审链为 t_borrow_extension.id */
    private Long extensionId;

    private Integer stepNo;

    /** 本步应签角色：BORROW_DEPT/COLLECTION_LEADER/DIRECTOR */
    private String approverRole;

    /** 实签人（防代签：接口按角色校验与应签人姓名一致由调用方登记） */
    private String approverName;

    /** APPROVE/REJECT */
    private String decision;

    private String opinion;

    /** 是否高风险追加的馆长签名步 1/0 */
    private Integer directorSign;

    private LocalDateTime signedAt;
}
