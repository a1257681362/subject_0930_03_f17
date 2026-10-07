package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 补修单：开放展示后发现问题的处置单。
 * 开立不触碰原开放记录（t_open_display_grant 保持原样）；
 * 闭环只置 CLOSED 留痕，记录保留。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_repair_supplement")
public class RepairSupplement extends BaseEntity {

    /** 补修单号，全局唯一 */
    private String orderNo;

    private Long projectId;

    private Long relicId;

    /** 关联的开放展示标记（原记录保留，不随补修改写） */
    private Long grantId;

    /** 开放后发现的问题描述 */
    private String issue;

    /** OPEN / CLOSED */
    private String status;

    private String createdBy;

    private LocalDateTime createdAt;

    private String closedBy;

    private LocalDateTime closedAt;

    private String closeNote;
}
