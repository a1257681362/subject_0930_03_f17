package com.evops.dto;

import lombok.Data;

/**
 * 借阅批准后的出库登记（museum-10）。
 * 存在提前收回补偿（RECALL）未闭环时禁止出库。
 */
@Data
public class BorrowLendRequest {

    /** 出库交接人 */
    private String lentBy;
}
