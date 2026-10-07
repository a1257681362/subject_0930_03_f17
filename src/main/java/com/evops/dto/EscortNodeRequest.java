package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.time.LocalDateTime;

/**
 * 押运节点登记：封箱/交接/到馆/开箱按序登记。
 * abnormal=true 时必须填异常原因，登记后后续节点暂停，待异常解除。
 * 开箱（OPEN_BOX）必须提供两名不同见证人 witnessA/witnessB。
 */
@Data
public class EscortNodeRequest {

    @NotBlank(message = "节点类型不能为空")
    private String nodeType;

    @NotBlank(message = "处理人不能为空")
    private String handler;

    private LocalDateTime recordedAt;

    private String remark;

    /** 是否异常，默认正常 */
    private Boolean abnormal;

    /** 异常原因（abnormal=true 时必填） */
    private String anomalyReason;

    /** 开箱见证人甲（OPEN_BOX 必填） */
    private String witnessA;

    /** 开箱见证人乙（OPEN_BOX 必填，须与甲不同） */
    private String witnessB;
}
