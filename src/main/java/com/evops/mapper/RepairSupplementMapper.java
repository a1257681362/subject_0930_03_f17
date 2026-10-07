package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.RepairSupplement;
import org.apache.ibatis.annotations.Select;

public interface RepairSupplementMapper extends BaseMapper<RepairSupplement> {

    /** 补修单号取号（H2 序列），插入前即确定且全库唯一 */
    @Select("SELECT NEXT VALUE FOR seq_repair_supplement_no")
    Long nextSupplementSeq();
}
