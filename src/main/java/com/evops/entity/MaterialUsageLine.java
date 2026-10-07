package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 领用明细行：一行一种批号材料。
 * 材料名称、批号、类别、单位、操作者、部位在领用时固化为快照，
 * 之后材料目录或批号发生任何变更都不影响历史工单。
 * reviewStatus: NONE 无需复核 / PENDING 待复核 / CONFIRMED 已复核确认。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_material_usage_line")
public class MaterialUsageLine extends BaseEntity {

    private Long usageId;

    private Long batchId;

    private Long materialId;

    /** 领用时的材料名称快照 */
    private String materialName;

    /** 领用时的批号快照 */
    private String batchNo;

    /** 领用时的材料类别快照 */
    private String category;

    /** 领用时的计量单位快照 */
    private String unit;

    /** 计划用量 */
    private BigDecimal planQty;

    /** 实际用量（同时是库存实际扣减与可退料上限） */
    private BigDecimal actualQty;

    /** 使用部位 */
    private String applyPart;

    /** 操作者（实际领料/用料人），不可为空、不可被后续流程覆盖 */
    private String operator;

    /** 实际相对计划的偏差百分比：abs(actual-plan)/plan*100 */
    private BigDecimal variancePct;

    /** NONE / PENDING / CONFIRMED */
    private String reviewStatus;
}
