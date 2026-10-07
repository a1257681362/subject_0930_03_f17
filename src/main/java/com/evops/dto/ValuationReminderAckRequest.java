package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 估值过期提醒人工确认：OPEN -> ACKNOWLEDGED。
 * 确认只关闭提醒，不改变估值版本状态，也不影响借展归还。
 */
@Data
public class ValuationReminderAckRequest {

    @NotBlank(message = "确认人不能为空")
    private String acknowledgedBy;

    private String ackNote;
}
