package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.EnvEvent;
import org.apache.ibatis.annotations.Select;

public interface EnvEventMapper extends BaseMapper<EnvEvent> {

    /** 事件单号取号（H2 序列），事件号在插入前即确定且全库唯一 */
    @Select("SELECT NEXT VALUE FOR seq_env_event_no")
    Long nextEventNo();
}
