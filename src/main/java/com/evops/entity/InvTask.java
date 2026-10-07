package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 展览借品清点任务：按展厅生成，一轮一行。
 * 基线版本任务内递增；baselineEventId 为基线时台账最大 id（借还变更冲突检测位点）。
 * status: OPEN 清点中 / CLOSED 已关闭（关闭后只能新增补充记录，原结果不重写）。
 * openKey 稀疏唯一：OPEN 时 = hallId，保证一展厅同时只有一个在途任务。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_inv_task")
public class InvTask extends BaseEntity {

    private String taskNo;

    private Long hallId;

    /** 展厅内轮次号，从 1 递增 */
    private Integer roundNo;

    /** 当前基线版本，从 1 递增；重基线只追加新版本 */
    private Integer baselineVersion;

    /** 基线时 t_relic_ledger 最大 id（借还变更冲突检测位点） */
    private Long baselineEventId;

    private LocalDateTime baselineAt;

    /** 当前版本基线应清点数 */
    private Integer expectedCount;

    /** 关闭时固化的结果汇总（原结果不再改写） */
    private Integer matchedCount;
    private Integer missingCount;
    private Integer misplacedCount;
    private Integer abnormalCount;

    private String closedBy;

    private LocalDateTime closedAt;

    /** OPEN 时 = hallId，关闭后置空（稀疏唯一） */
    private Long openKey;

    /** OPEN / CLOSED */
    private String status;
}
