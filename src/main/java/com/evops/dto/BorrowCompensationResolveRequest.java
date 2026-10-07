package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.math.BigDecimal;

/**
 * 借阅补偿闭环确认（museum-10）：
 * RECALL 提前收回要求借阅已不在途（已撤销/驳回/归还）；
 * SUPPLEMENT_CONFIRM 为等级与保险差异补充确认，可登记更正后保险金额。
 */
@Data
public class BorrowCompensationResolveRequest {

    @NotBlank(message = "确认人不能为空")
    private String resolvedBy;

    @NotBlank(message = "闭环说明不能为空")
    private String note;

    /** 补充确认后的保险金额（选填，跨馆区仍按馆区脱敏） */
    private BigDecimal insuranceAfter;
}
