package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 年度报表项目明细快照行：一报表一项目一行，生成时固化，之后不再改写。
 * {@code pendingSupplement}=1 表示该完成项目缺少验收资料（待补）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_annual_report_project_line")
public class AnnualReportProjectLine extends BaseEntity {

    private Long reportId;

    private Long projectId;

    private String projectNo;

    private Long relicId;

    private String relicNo;

    private String relicName;

    /** 文物所属馆区（导出裁剪维度） */
    private String campusCode;

    /** 文物保密级别（ONE/TWO/THREE，导出裁剪维度） */
    private String secretLevel;

    /** 生成时项目状态快照 */
    private String projectStatus;

    private String client;

    /** 计入立项数（按口径归属时间与状态命中） */
    private Integer countedProject;

    /** 计入完成数（可与立项跨年） */
    private Integer countedCompleted;

    /** 是否已通过项目验收（t_repair_acceptance） */
    private Integer accepted;

    /** 验收资料是否齐备（museum-14 成果报告 PASSED） */
    private Integer hasAcceptDocs;

    /** 完成但缺验收资料 → 待补 */
    private Integer pendingSupplement;

    private LocalDateTime createdAt;
}
