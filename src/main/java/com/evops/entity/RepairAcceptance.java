package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 验收记录：一个修复项目至多一条，验收后项目闭环为 ACCEPTED。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_repair_acceptance")
public class RepairAcceptance extends BaseEntity {

    private Long projectId;

    /** 验收人 */
    private String acceptedBy;

    private LocalDateTime acceptedAt;

    /** PASS / CONDITIONAL */
    private String result;

    /** 验收意见 */
    private String opinion;
}
