package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.PackNotifyOutbox;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface PackNotifyOutboxMapper extends BaseMapper<PackNotifyOutbox> {

    /**
     * 行级认领一条待投递通知：仅 PENDING 可被认领为 SENDING。
     * 并发（手动重试/定时补偿/重启恢复）下数据库条件更新保证只有一个调用者认领成功，
     * 认领后由该调用者执行实际投递；SENT 行永远不会再次匹配，恢复后不重复通知。
     *
     * @return 受影响行数：1 认领成功，0 已被他人认领或已送达
     */
    @Update("UPDATE t_pack_notify_outbox SET status='SENDING', attempts=attempts+1, update_time=CURRENT_TIMESTAMP "
            + "WHERE id=#{id} AND status='PENDING'")
    int claim(@Param("id") Long id);
}
