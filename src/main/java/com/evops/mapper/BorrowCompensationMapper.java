package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.BorrowCompensation;
import org.apache.ibatis.annotations.Select;

public interface BorrowCompensationMapper extends BaseMapper<BorrowCompensation> {

    /** 借阅补偿单号取号（H2 序列） */
    @Select("SELECT NEXT VALUE FOR seq_borrow_comp_no")
    Long nextCompSeq();
}
