package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.BorrowApplication;
import org.apache.ibatis.annotations.Select;

public interface BorrowApplicationMapper extends BaseMapper<BorrowApplication> {

    /** 借阅申请单号取号（H2 序列），插入前即确定且全库唯一 */
    @Select("SELECT NEXT VALUE FOR seq_borrow_app_no")
    Long nextAppSeq();
}
