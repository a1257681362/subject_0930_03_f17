package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.AnnualReportAlert;
import org.apache.ibatis.annotations.Select;

public interface AnnualReportAlertMapper extends BaseMapper<AnnualReportAlert> {

    /** 失败告警单号取号（H2 序列） */
    @Select("SELECT NEXT VALUE FOR seq_annual_alert_no")
    Long nextAlertSeq();
}
