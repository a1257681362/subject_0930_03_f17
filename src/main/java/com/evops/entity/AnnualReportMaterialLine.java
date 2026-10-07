package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 年度报表材料消耗明细快照行：一领用单一行。
 * 净消耗 netQty = 实际领用 - 累计退料（可跨材料类别直接求和，单位差异在口径版本中声明）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_annual_report_material_line")
public class AnnualReportMaterialLine extends BaseEntity {

    private Long reportId;

    private Long usageId;

    private String usageNo;

    private Long projectId;

    private String projectNo;

    private Long relicId;

    private String relicNo;

    private String relicName;

    private String campusCode;

    private String secretLevel;

    /** 实际领用量（明细行 actual_qty 合计） */
    private BigDecimal issuedQty;

    /** 累计退料量 */
    private BigDecimal returnedQty;

    /** 实际净消耗 */
    private BigDecimal netQty;

    private LocalDateTime usageAt;

    private LocalDateTime createdAt;
}
