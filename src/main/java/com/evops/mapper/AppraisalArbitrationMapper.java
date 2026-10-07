package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.AppraisalArbitration;
import org.apache.ibatis.annotations.Select;

public interface AppraisalArbitrationMapper extends BaseMapper<AppraisalArbitration> {

    /** 仲裁单编号取号（H2 序列） */
    @Select("SELECT NEXT VALUE FOR seq_appraisal_arb_no")
    Long nextArbitrationSeq();
}
