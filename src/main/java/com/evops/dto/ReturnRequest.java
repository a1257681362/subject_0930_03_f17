package com.evops.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ReturnRequest {

    private LocalDateTime returnedAt;

    /** 归还时品相 */
    private String returnCondition;

    /** 归还接收人 */
    private String receiver;
}
