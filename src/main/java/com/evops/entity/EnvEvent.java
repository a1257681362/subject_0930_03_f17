package com.evops.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 环境异常事件：按 (展厅, 点位, 指标, 材质) 归并连续超限。
 * 同点位同指标但违反不同材质规则时分别成事件（不同材质阈值不同）。
 *
 * <ul>
 *   <li>OPEN：异常持续中，连续超限并入本事件（更新 last/peak/count），不新开；</li>
 *   <li>HANDLING：已指派处置人处置中，仍接受连续超限并入；</li>
 *   <li>RESOLVED：指标恢复正常，等待保管员复核；</li>
 *   <li>CLOSED：保管员复核通过关闭。关闭前必须有处置人与现场照片。</li>
 * </ul>
 *
 * mergeKey = hall|point|metric|material，仅 OPEN/HANDLING 持有非空值并受唯一约束保护；
 * RESOLVED/CLOSED 置 null（H2 唯一约束允许多个 NULL），再次超限即可开新事件。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_env_event")
public class EnvEvent extends BaseEntity {

    private String eventNo;

    private Long hallId;

    private String pointCode;

    /** TEMP / HUMIDITY / LUX */
    private String metric;

    /** 违反的是哪种材质的规则（事件按材质分别开立） */
    private String material;

    /** 连续超限归并锚点 hall|point|metric|material，仅在途事件非空；
     *  ALWAYS 策略保证恢复/关闭置 null 能真正落库（默认更新策略会忽略 null） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String mergeKey;

    /** 事件首次越限读数采用的规则版本快照；并入读数若来自新版本，仅更新台账说明 */
    private Long ruleVersionId;
    private String ruleVersionNo;

    private BigDecimal thresholdLow;
    private BigDecimal thresholdHigh;

    private BigDecimal firstValue;
    private BigDecimal lastValue;
    private BigDecimal peakValue;

    private Integer readingCount;

    private Long firstReadingId;
    private Long lastReadingId;

    private LocalDateTime firstAt;
    private LocalDateTime lastAt;

    /** 处置人（关闭前必填） */
    private String handler;
    private String handleRemark;
    private LocalDateTime handledAt;

    /** 保管员复核 */
    private String reviewBy;
    private String reviewRemark;
    private LocalDateTime reviewedAt;

    private String closeRemark;
    private LocalDateTime closedAt;

    /** OPEN / HANDLING / RESOLVED / CLOSED */
    private String status;
}
