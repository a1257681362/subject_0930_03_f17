package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 诱捕片：按点位布放/更换。
 * 更换只把旧片置 REPLACED 并布放新片——旧片与其监测记录永不删除。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_pest_trap_card")
public class PestTrapCard extends BaseEntity {

    private String cardNo;

    private Long pointId;

    private LocalDateTime deployedAt;

    private String deployedBy;

    private LocalDateTime replacedAt;

    private String replacedBy;

    /** ACTIVE 在布 / REPLACED 已更换 */
    private String status;
}
