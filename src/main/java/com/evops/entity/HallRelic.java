package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 展厅在展文物：材质决定适用的规则条目，点位（展柜/区域）是采样与事件归并单元。
 * status: ON_DISPLAY 展出中 / WITHDRAWN 已撤展（撤展后不再采样，历史保留）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_hall_relic")
public class HallRelic extends BaseEntity {

    private Long hallId;

    private Long relicId;

    /** 建档材质快照，规则条目按此材质匹配 */
    private String material;

    /** 巡检点位编码（展柜/区域），同一展厅内同一点位可有若干件文物 */
    private String pointCode;

    /** 陈展编号（展签） */
    private String displayNo;

    /** 箱号（museum-12 清点基线快照用） */
    private String boxNo;

    /** ON_DISPLAY / WITHDRAWN */
    private String status;
}
