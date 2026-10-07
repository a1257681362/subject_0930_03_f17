package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.InsuranceValuationVersion;
import org.apache.ibatis.annotations.Select;

public interface InsuranceValuationVersionMapper extends BaseMapper<InsuranceValuationVersion> {

    /** 估值单号取号（H2 序列） */
    @Select("SELECT NEXT VALUE FOR seq_ins_valuation_no")
    Long nextValuationSeq();
}
