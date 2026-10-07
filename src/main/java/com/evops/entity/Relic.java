package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 文物档案：记录材质、年代、来源、保存等级。
 * 撤销只把 status 置为 REVOKED，记录仍保留。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_relic")
public class Relic extends BaseEntity {

    /** 文物编号，全局唯一 */
    private String relicNo;

    private String name;

    /** 文物品类，如青铜器、书画 */
    private String category;

    /** 材质 */
    private String material;

    /** 年代 */
    private String era;

    /** 来源 */
    private String source;

    /** 保存等级 ONE/TWO/THREE */
    private String preservationGrade;

    /** 所属馆区编码（museum-10：借阅审批与保险资料按馆区隔离），默认 MAIN 主馆区 */
    private String campusCode;

    private String description;

    /** ACTIVE / REVOKED */
    private String status;

    private String revokeReason;

    private LocalDateTime revokedAt;
}
