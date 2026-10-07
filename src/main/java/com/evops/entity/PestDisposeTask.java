package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 虫害处置任务：监测超阈值即生成，关联点位责任区域。
 * 外包人员只能提交现场数据，不能关闭任务；仅馆内人员可关闭。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_pest_dispose_task")
public class PestDisposeTask extends BaseEntity {

    private String taskNo;

    /** 触发任务的监测记录（一记录至多一任务） */
    private Long recordId;

    private Long pointId;

    /** 责任区域快照（自点位带入） */
    private String responsibleArea;

    /** 超阈值虫种（、分隔） */
    private String overSpecies;

    /** 判定依据的规则版本快照 */
    private Long ruleVersionId;
    private String ruleVersionNo;

    /** OPEN 待处置 / CLOSED 已关闭 */
    private String status;

    private String closedBy;

    private LocalDateTime closedAt;

    private String closeNote;
}
