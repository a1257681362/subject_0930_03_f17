package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.PackOrder;
import org.apache.ibatis.annotations.Select;

public interface PackOrderMapper extends BaseMapper<PackOrder> {

    /** 装箱单号取号（H2 序列 PK-000001），插入前确定且全库唯一 */
    @Select("SELECT NEXT VALUE FOR seq_pack_order_no")
    Long nextOrderNo();
}
