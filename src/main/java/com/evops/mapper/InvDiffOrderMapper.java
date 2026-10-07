package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.InvDiffOrder;
import org.apache.ibatis.annotations.Select;

public interface InvDiffOrderMapper extends BaseMapper<InvDiffOrder> {

    /** 差异单号取号（H2 序列），单号在插入前即确定且全库唯一 */
    @Select("SELECT NEXT VALUE FOR seq_inv_diff_no")
    Long nextDiffNo();
}
