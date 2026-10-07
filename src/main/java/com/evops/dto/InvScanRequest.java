package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.time.LocalDateTime;

/** 清点扫描：工作人员扫描箱号与展签 */
@Data
public class InvScanRequest {

    /** 扫描箱号 */
    private String boxNo;

    @NotBlank(message = "扫描展签不能为空")
    private String displayNo;

    /** 扫描点位（可选） */
    private String pointCode;

    @NotBlank(message = "扫描人不能为空")
    private String scannedBy;

    /** 扫描时间（缺省取当前时间） */
    private LocalDateTime scannedAt;
}
