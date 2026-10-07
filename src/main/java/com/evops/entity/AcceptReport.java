package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 修复成果报告：修复师按轮次提交（roundNo 项目内递增，旧轮次保留）。
 * 验收清单按文物材质 + 报告工艺从配置模板生成；
 * 状态：IN_REVIEW 确认中 / RECTIFICATION 整改中 / PASSED 验收通过（终态）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_repair_accept_report")
public class AcceptReport extends BaseEntity {

    private Long projectId;

    /** 项目内报告轮次，从 1 递增；整改后重新提交形成新轮次 */
    private Integer roundNo;

    /** 修复工艺（与文物材质一起决定验收清单模板） */
    private String craft;

    /** 成果摘要 */
    private String summary;

    /** 指定确认人：馆内专家 */
    private String expertName;

    /** 指定确认人：保管员 */
    private String custodianName;

    /** 提交人（修复师） */
    private String submittedBy;

    private LocalDateTime submittedAt;

    /** IN_REVIEW / RECTIFICATION / PASSED */
    private String status;

    private LocalDateTime closedAt;
}
