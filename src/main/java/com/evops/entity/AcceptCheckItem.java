package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 验收清单项：报告提交时按材质+工艺模板固化，不随配置变更改写。
 * 馆内专家与保管员分别逐项确认（expertResult / custodianResult），
 * 任一角色任一项缺项（MISSING）即整整改。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_repair_accept_item")
public class AcceptCheckItem extends BaseEntity {

    private Long reportId;

    /** 报告内条目序号，从 1 递增 */
    private Integer itemNo;

    /** 检查项内容（生成时从模板固化） */
    private String content;

    /** 馆内专家确认结果：NULL 未确认 / OK / MISSING */
    private String expertResult;

    /** 保管员确认结果：NULL 未确认 / OK / MISSING */
    private String custodianResult;
}
