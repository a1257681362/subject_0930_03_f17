package com.evops.dto;

import lombok.Data;

/**
 * 「博物馆文物修复档案起步」结果。
 * 无论首次还是重复请求，返回的样本编号/主键都指向同一条样本；
 * created=false 表示本次请求命中幂等锁、未产生第二条记录。
 */
@Data
public class BootstrapResult {

    private boolean created;
    private String message;

    private Long relicId;
    private String relicNo;
    private Long intakeId;
    private String intakeNo;
    private Long loanId;
    private String loanNo;
    private Long projectId;
    private String projectNo;
    private Integer stageCount;
    private Long acceptanceId;

    /** 修复材料核算（museum-3）：样本领用单行数、待复核数、样本领用单 */
    private Integer materialLineCount;
    private Integer openReviewCount;
    private Long usageId;
    private String usageNo;
}
