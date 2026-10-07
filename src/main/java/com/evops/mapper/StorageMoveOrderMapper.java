package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.StorageMoveOrder;
import org.apache.ibatis.annotations.Select;

public interface StorageMoveOrderMapper extends BaseMapper<StorageMoveOrder> {

    /** 移动单号取号（H2 序列） */
    @Select("SELECT NEXT VALUE FOR seq_storage_move_order_no")
    Long nextMoveOrderSeq();
}
