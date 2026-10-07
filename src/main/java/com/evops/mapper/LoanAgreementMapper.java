package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.LoanAgreement;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface LoanAgreementMapper extends BaseMapper<LoanAgreement> {

    /**
     * 借展协议行锁：对协议行执行一次 UPDATE 占位，H2 行级排他锁持有到当前
     * 事务提交——同一协议的并发归还验收在此串行，先提交者生效，
     * 后者在锁内回读到已完成的验收后幂等回放或被拒绝。必须在事务内调用。
     * （不用 SELECT ... FOR UPDATE：映射结束即关闭 ResultSet，行锁随之释放。）
     */
    @Update("UPDATE t_loan_agreement SET update_time = CURRENT_TIMESTAMP WHERE id = #{id}")
    int lockRow(@Param("id") Long id);
}
