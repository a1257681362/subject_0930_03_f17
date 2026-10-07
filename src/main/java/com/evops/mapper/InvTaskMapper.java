package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.InvTask;
import org.apache.ibatis.annotations.Select;

public interface InvTaskMapper extends BaseMapper<InvTask> {

    /** 清点任务单号取号（H2 序列），任务号在插入前即确定且全库唯一 */
    @Select("SELECT NEXT VALUE FOR seq_inv_task_no")
    Long nextTaskNo();
}
