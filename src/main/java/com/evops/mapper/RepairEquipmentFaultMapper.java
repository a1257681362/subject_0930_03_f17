package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.RepairEquipmentFault;
import org.apache.ibatis.annotations.Select;

public interface RepairEquipmentFaultMapper extends BaseMapper<RepairEquipmentFault> {

    /** 故障单号取号（H2 序列），插入前即确定且全库唯一 */
    @Select("SELECT NEXT VALUE FOR seq_repair_fault_no")
    Long nextFaultSeq();
}
