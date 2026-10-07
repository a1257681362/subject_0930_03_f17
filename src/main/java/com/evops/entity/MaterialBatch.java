package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 材料批号库存：颜料/黏合剂/保护剂按批号入库。
 * 同材料同批号重复入库累加 stockQty；批号全库唯一。
 * 过期（expireDate 早于当前日期）或非在库状态不能领用。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_material_batch")
public class MaterialBatch extends BaseEntity {

    private Long materialId;

    /** 批号，全库唯一 */
    private String batchNo;

    /** 当前库存数量 */
    private BigDecimal stockQty;

    /** 累计入库数量（只随入库累加） */
    private BigDecimal inboundQty;

    private LocalDate producedDate;

    /** 到期日期；早于当前日期即过期，禁止领用 */
    private LocalDate expireDate;

    private String supplier;

    private LocalDateTime inboundAt;

    private String inboundBy;

    /** IN_STOCK 在库 / EXHAUSTED 用尽 / DISPOSED 报废 */
    private String status;
}
