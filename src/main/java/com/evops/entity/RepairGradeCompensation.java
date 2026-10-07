package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 修复部分成功（验收附条件通过 CONDITIONAL）保护等级补偿（museum-10）：
 * 一项目至多一条。验收时固化等级前/后，等级调整立即落到文物档案；
 * 借阅侧成对开立 t_borrow_compensation 并互持 id，借阅补偿闭环后本单方可确认闭环。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_repair_grade_compensation")
public class RepairGradeCompensation extends BaseEntity {

    private String compensationNo;

    private Long projectId;

    private Long relicId;

    private Long acceptanceId;

    /** 验收时原保护等级 */
    private String gradeBefore;

    /** 验收附条件给出的调整后等级（已落文物档案） */
    private String gradeAfter;

    private String reason;

    /** OPEN/RESOLVED */
    private String status;

    private String openedBy;

    private LocalDateTime openedAt;

    /** 补偿确认时的最终等级（可再次更正） */
    private String resolvedGrade;

    private String resolvedBy;

    private LocalDateTime resolvedAt;

    private String resolveNote;

    /** 互指的借阅补偿 id（无借阅影响时为空） */
    private Long borrowCompensationId;
}
