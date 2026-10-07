package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.InsuranceValuationSnapshot;
import org.apache.ibatis.annotations.Select;

public interface InsuranceValuationSnapshotMapper extends BaseMapper<InsuranceValuationSnapshot> {

    /** 借展签约估值快照单号取号（H2 序列） */
    @Select("SELECT NEXT VALUE FOR seq_ins_valuation_snapshot_no")
    Long nextSnapshotSeq();
}
