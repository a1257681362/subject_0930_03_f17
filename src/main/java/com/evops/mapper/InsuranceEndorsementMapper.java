package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.InsuranceEndorsement;
import org.apache.ibatis.annotations.Select;

public interface InsuranceEndorsementMapper extends BaseMapper<InsuranceEndorsement> {

    /** 保险批单号取号（H2 序列） */
    @Select("SELECT NEXT VALUE FOR seq_insurance_endorsement_no")
    Long nextEndorsementSeq();
}
