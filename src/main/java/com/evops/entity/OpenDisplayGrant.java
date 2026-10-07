package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 开放展示标记：只有成果验收通过（PASSED）后才能设置。
 * 撤销只把 status 置为 REVOKED 留痕，记录保留；
 * 公众页面只展示 ACTIVE 标记的对外摘要与授权影像。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_open_display_grant")
public class OpenDisplayGrant extends BaseEntity {

    private Long projectId;

    private Long relicId;

    /** 验收通过的那轮成果报告 */
    private Long reportId;

    /** 对外摘要（公众页面展示口径，与内部成果摘要分离） */
    private String summary;

    /** ACTIVE / REVOKED */
    private String status;

    private String grantedBy;

    private LocalDateTime grantedAt;

    private String revokeReason;

    private LocalDateTime revokedAt;
}
