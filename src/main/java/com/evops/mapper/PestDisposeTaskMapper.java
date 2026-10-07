package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.PestDisposeTask;
import org.apache.ibatis.annotations.Select;

public interface PestDisposeTaskMapper extends BaseMapper<PestDisposeTask> {

    /** 处置任务单号取号（H2 序列），任务号在插入前即确定且全库唯一 */
    @Select("SELECT NEXT VALUE FOR seq_pest_task_no")
    Long nextTaskNo();
}
