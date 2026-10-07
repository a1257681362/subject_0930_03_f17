package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.AnnualReport;
import org.apache.ibatis.annotations.Select;

public interface AnnualReportMapper extends BaseMapper<AnnualReport> {

    /** 年度报表编号取号（H2 序列，撤回重提领取新号，不复用旧号） */
    @Select("SELECT NEXT VALUE FOR seq_annual_report_no")
    Long nextReportSeq();
}
