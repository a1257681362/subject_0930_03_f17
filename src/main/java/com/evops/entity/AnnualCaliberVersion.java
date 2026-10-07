package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 年度报表指标口径版本（museum-16）：只追加。
 *
 * <p>五项指标（立项数/完成数/材料消耗/借展次数/异常事件）的归属时间字段、
 * 计入状态等口径随版本固化为 {@code caliberJson}。发布新版本时旧版本置
 * {@code SUPERSEDED} 原样保留；年度报表一经生成即绑定当时口径版本 id，
 * 之后即使口径发布新版本，核对仍按原版本完成。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_annual_caliber_version")
public class AnnualCaliberVersion extends BaseEntity {

    /** 口径版本号，全库从 1 递增 */
    private Integer versionNo;

    /** 版本名称（如「2025 年度口径」） */
    private String name;

    /** 五项指标口径（JSON 固化，生成后永不改写） */
    private String caliberJson;

    /** DRAFT / ACTIVE / SUPERSEDED */
    private String status;

    /** 修订原因 */
    private String changeReason;

    private String publishedBy;

    private LocalDateTime publishedAt;

    private LocalDateTime supersededAt;
}
