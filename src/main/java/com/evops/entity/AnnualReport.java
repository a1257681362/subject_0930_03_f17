package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 文物修复年度报表（museum-16）：生成即不可变的归档快照头。
 *
 * <p>同一年度同一口径版本每次生成批次号 {@code batchNo} 递增、报表编号不同；
 * 撤回只置 {@code REVOKED}（快照、明细、异常凭证全部保留），同批数据重提另出
 * 新批次、新编号、新生成时间，{@code revokedOfId} 指回被撤回批次——
 * 编号、统计口径版本、时间三者都可辨。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_annual_report")
public class AnnualReport extends BaseEntity {

    /** 报表编号，全库唯一，形如 YR-2025-000002（含批次序号） */
    private String reportNo;

    /** 统计年度（列名 report_year，避开 H2 保留字 YEAR） */
    private Integer reportYear;

    /** 批次号：同年度同口径版本内从 1 递增，撤回重提出新批次 */
    private Integer batchNo;

    /** 本报表核对所依据的口径版本 id（绑定后不随口径新版本改变） */
    private Long caliberVersionId;

    private Integer caliberVersionNo;

    /** 立项数 */
    private Integer projectCount;

    /** 完成数（修复完成，含缺验收资料待补项目） */
    private Integer completedCount;

    /** 材料实际净消耗合计（实际领用 - 退料） */
    private BigDecimal materialConsumption;

    /** 借展次数（借展协议版本签署次数） */
    private Integer loanCount;

    /** 异常事件数（环境异常 + 修复设备故障） */
    private Integer anomalyCount;

    /** 缺少验收资料、标记待补的完成项目数 */
    private Integer pendingSupplementCount;

    /** 生成时的馆区范围（null=全馆区） */
    private String campusScope;

    private String generatedBy;

    private LocalDateTime generatedAt;

    /** MANUAL / SCHEDULED */
    private String triggeredBy;

    /** 定时生成时关联的任务日志 id */
    private Long jobLogId;

    /** GENERATED / REVOKED */
    private String status;

    private String revokeReason;

    private String revokedBy;

    private LocalDateTime revokedAt;

    /** 撤回重提链：本批次由哪一被撤回批次重提而来 */
    private Long revokedOfId;
}
