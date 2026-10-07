package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 入藏登记：一件文物仅一条，关联尺寸与库位；照片见 {@link RelicPhoto}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_relic_intake")
public class RelicIntake extends BaseEntity {

    private Long relicId;

    /** 入藏单号 */
    private String intakeNo;

    /** 高（毫米） */
    private BigDecimal heightMm;

    /** 宽（毫米） */
    private BigDecimal widthMm;

    /** 厚/深（毫米） */
    private BigDecimal depthMm;

    /** 重量（克） */
    private BigDecimal weightG;

    /** 库位 */
    private String location;

    private LocalDateTime intakeTime;

    /** 接收人 */
    private String receiver;

    /** 入藏时品相记录 */
    private String conditionNote;

    /** ACTIVE / REVOKED */
    private String status;
}
