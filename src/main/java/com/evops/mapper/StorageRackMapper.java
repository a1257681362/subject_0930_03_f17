package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.StorageRack;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface StorageRackMapper extends BaseMapper<StorageRack> {

    /**
     * 密集架行锁（互斥设备串行化）：对架体行执行一次 UPDATE 占位，
     * H2 行级排他锁持有到当前事务提交——并发的第二个预约在此阻塞，
     * 待前者提交后再在事务内复查格位占用与时段重叠。必须在事务内调用。
     * （不用 SELECT ... FOR UPDATE：@Select 映射结束即关闭 ResultSet，行锁随之释放。）
     */
    @Update("UPDATE t_storage_rack SET update_time = CURRENT_TIMESTAMP WHERE id = #{id}")
    int lockRow(@Param("id") Long id);
}
