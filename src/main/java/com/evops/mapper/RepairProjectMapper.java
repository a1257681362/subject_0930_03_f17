package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.RepairProject;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface RepairProjectMapper extends BaseMapper<RepairProject> {

    /**
     * 修复项目行锁：对项目行执行一次 UPDATE 占位，H2 行级排他锁持有到当前
     * 事务提交——同一项目同一操作的并发请求在此串行，先提交者生效，
     * 后者在锁内复查状态后被拒绝并落 REJECTED 审计。必须在事务内调用。
     * （不用 SELECT ... FOR UPDATE：映射结束即关闭 ResultSet，行锁随之释放。）
     */
    @Update("UPDATE t_repair_project SET update_time = CURRENT_TIMESTAMP WHERE id = #{id}")
    int lockRow(@Param("id") Long id);
}
