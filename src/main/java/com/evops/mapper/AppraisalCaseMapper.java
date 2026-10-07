package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.AppraisalCase;
import org.apache.ibatis.annotations.Select;

public interface AppraisalCaseMapper extends BaseMapper<AppraisalCase> {

    /** 案件编号取号（H2 序列） */
    @Select("SELECT NEXT VALUE FOR seq_appraisal_case_no")
    Long nextCaseSeq();
}
