package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.StorageReservation;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface StorageReservationMapper extends BaseMapper<StorageReservation> {

    /** 预约单号取号（H2 序列），插入前即确定且全库唯一 */
    @Select("SELECT NEXT VALUE FOR seq_storage_reservation_no")
    Long nextReservationSeq();

    /**
     * 超时释放：只把「已预约 RESERVED 且已到超时时刻」的单置 EXPIRED 并清空格位持有键。
     * <b>WHERE 不含 MOVING——已开始移动的格位永不释放</b>。
     * 返回受影响行数，供调用方逐条补写台账。
     */
    @Update("UPDATE t_storage_reservation SET status = 'EXPIRED', hold_key = NULL, "
            + "update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND status = 'RESERVED' AND expire_at < #{now}")
    int expireIfTimedOut(@Param("id") Long id, @Param("now") LocalDateTime now);
}
