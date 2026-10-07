package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.PackSubstitute;
import org.apache.ibatis.annotations.Select;

public interface PackSubstituteMapper extends BaseMapper<PackSubstitute> {

    /** 替代包装审批单号取号（H2 序列 PS-000001） */
    @Select("SELECT NEXT VALUE FOR seq_pack_substitute_no")
    Long nextSubstituteNo();
}
