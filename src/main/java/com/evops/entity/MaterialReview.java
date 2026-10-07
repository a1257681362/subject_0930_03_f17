package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用量差异复核：每条超阈值明细行全生命周期只有一条记录（uk_review_line）。
 * 定时任务凭此唯一约束实现重启幂等：重复扫描不会多写复核记录、不丢操作者。
 * status: OPEN 待复核 / CONFIRMED 已确认。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_material_review")
public class MaterialReview extends BaseEntity {

    /** 触发复核的领用明细行，唯一 */
    private Long lineId;

    private Long usageId;

    private Long projectId;

    private Long relicId;

    private BigDecimal planQty;

    private BigDecimal actualQty;

    private BigDecimal variancePct;

    /** 触发时生效的差异阈值（%） */
    private BigDecimal thresholdPct;

    /** 从领用明细行原样带来的操作者，复核流程不覆盖 */
    private String operator;

    private String applyPart;

    /** OPEN / CONFIRMED */
    private String status;

    private LocalDateTime detectedAt;

    private String confirmedBy;

    private LocalDateTime confirmedAt;

    private String confirmNote;
}
