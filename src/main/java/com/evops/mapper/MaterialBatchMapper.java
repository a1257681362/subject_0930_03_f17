package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.MaterialBatch;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

public interface MaterialBatchMapper extends BaseMapper<MaterialBatch> {

    /**
     * 原子扣减库存：仅当库存充足时生效，避免并发领用导致超扣。
     * 扣到 0 的在库批号置为 EXHAUSTED；返回受影响行数（0 即库存不足）。
     */
    @Update("UPDATE t_material_batch SET stock_qty = stock_qty - #{qty}, "
            + "status = CASE WHEN status = 'IN_STOCK' AND stock_qty <= #{qty} "
            + "THEN 'EXHAUSTED' ELSE status END, update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND stock_qty >= #{qty}")
    int deductStock(@Param("id") Long id, @Param("qty") BigDecimal qty);

    /**
     * 退料加回原批号库存；仅 EXHAUSTED 批号因退料恢复为 IN_STOCK，
     * DISPOSED 报废批号不因退料复活。
     */
    @Update("UPDATE t_material_batch SET stock_qty = stock_qty + #{qty}, "
            + "status = CASE WHEN status = 'EXHAUSTED' THEN 'IN_STOCK' ELSE status END, "
            + "update_time = CURRENT_TIMESTAMP WHERE id = #{id}")
    int addStock(@Param("id") Long id, @Param("qty") BigDecimal qty);
}
