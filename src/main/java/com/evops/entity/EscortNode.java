package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 押运节点：封箱 SEAL -> 交接 HANDOVER -> 到馆 ARRIVAL -> 开箱 OPEN_BOX。
 * 协议内每类仅一条，必须按序登记；abnormal=1 后后续节点全部暂停，解除后才能继续。
 * 开箱（OPEN_BOX）必须有两名不同见证人。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_loan_escort_node")
public class EscortNode extends BaseEntity {

    private Long agreementId;

    /** 登记节点时协议当前已签版本快照 */
    private Long agreementVersionId;

    /** SEAL / HANDOVER / ARRIVAL / OPEN_BOX */
    private String nodeType;

    /** 固定顺序号 1..4 */
    private Integer seqNo;

    /** 处理人 */
    private String handler;

    private LocalDateTime recordedAt;

    private String remark;

    /** 0 正常 / 1 异常 */
    private Integer abnormal;

    /** 异常原因（abnormal=1 时必填） */
    private String anomalyReason;

    private String resolvedBy;

    private LocalDateTime resolvedAt;

    private String resolveRemark;

    /** 开箱见证人甲 */
    private String witnessA;

    /** 开箱见证人乙 */
    private String witnessB;
}
