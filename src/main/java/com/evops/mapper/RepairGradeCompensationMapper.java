package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.RepairGradeCompensation;
import org.apache.ibatis.annotations.Select;

public interface RepairGradeCompensationMapper extends BaseMapper<RepairGradeCompensation> {

    /** 保护等级补偿单号取号（H2 序列） */
    @Select("SELECT NEXT VALUE FOR seq_grade_comp_no")
    Long nextCompSeq();
}
