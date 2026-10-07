package com.evops.dto;

import lombok.Data;

/** 关闭展厅（展览）：有未复核完成的清点差异时拒绝 */
@Data
public class HallCloseRequest {

    /** 关闭人 */
    private String closedBy;
}
