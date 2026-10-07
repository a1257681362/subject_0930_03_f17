package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.PestMonitorRecord;
import org.apache.ibatis.annotations.Select;

public interface PestMonitorRecordMapper extends BaseMapper<PestMonitorRecord> {

    /** 监测记录单号取号（H2 序列），记录号在插入前即确定且全库唯一 */
    @Select("SELECT NEXT VALUE FOR seq_pest_record_no")
    Long nextRecordNo();
}
