package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.InsuranceValuationReminder;
import org.apache.ibatis.annotations.Select;

public interface InsuranceValuationReminderMapper extends BaseMapper<InsuranceValuationReminder> {

    /** 估值过期提醒单号取号（H2 序列） */
    @Select("SELECT NEXT VALUE FOR seq_ins_valuation_reminder_no")
    Long nextReminderSeq();
}
