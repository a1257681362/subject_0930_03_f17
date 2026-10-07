package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.BorrowExtension;
import org.apache.ibatis.annotations.Select;

public interface BorrowExtensionMapper extends BaseMapper<BorrowExtension> {

    /** 延期单号取号（H2 序列），插入前即确定且全库唯一 */
    @Select("SELECT NEXT VALUE FOR seq_borrow_ext_no")
    Long nextExtSeq();
}
