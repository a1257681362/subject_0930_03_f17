package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 外部修复依赖装箱确认通知（发件箱）：一装箱单一条。
 *
 * <p>状态机 PENDING → SENDING → SENT。行级认领（条件 UPDATE）把手动重试、
 * 定时补偿与重启恢复串行化：SENT 永不重投，恢复后恰好通知一次。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_pack_notify_outbox")
public class PackNotifyOutbox extends BaseEntity {

    private Long orderId;
    /** 通知类型，当前固定 PACK_CONFIRM */
    private String notifyType;
    /** 幂等键（随通知发送给外部依赖，外部按此去重） */
    private String notifyKey;
    /** PENDING / SENDING / SENT */
    private String status;
    private Integer attempts;
    private String lastError;
    private LocalDateTime sentAt;
}
