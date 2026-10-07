package com.evops.gateway;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 博物馆文物修复外部依赖网关：装箱确认须通知外部修复系统。
 *
 * <p>实现必须如实反映依赖状态：<b>无响应/超时时抛出
 * {@link MuseumRepairUnavailableException}，绝不返回伪成功</b>。
 * 调用方（包装服务/补偿调度）据此让装箱单停留在待确认状态，恢复后再投递。</p>
 */
public interface MuseumRepairClient {

    /**
     * 通知「装箱已确认」。
     *
     * @param notice 通知内容（携带幂等键，外部依赖可按 notifyKey 去重）
     * @return 外部依赖回执（成功时）
     * @throws MuseumRepairUnavailableException 依赖无响应/超时/失败时
     */
    String notifyPackConfirmed(PackConfirmNotice notice);

    /** 发给外部修复依赖的装箱确认通知（字段即通知载荷快照） */
    class PackConfirmNotice {
        private final String notifyKey;
        private final String orderNo;
        private final String boxNo;
        private final Long relicId;
        private final Long agreementId;
        private final String specVersionNo;
        private final String packType;
        private final LocalDateTime confirmedAt;
        private final BigDecimal actualWeightG;

        public PackConfirmNotice(String notifyKey, String orderNo, String boxNo, Long relicId,
                                Long agreementId, String specVersionNo, String packType,
                                BigDecimal actualWeightG, LocalDateTime confirmedAt) {
            this.notifyKey = notifyKey;
            this.orderNo = orderNo;
            this.boxNo = boxNo;
            this.relicId = relicId;
            this.agreementId = agreementId;
            this.specVersionNo = specVersionNo;
            this.packType = packType;
            this.actualWeightG = actualWeightG;
            this.confirmedAt = confirmedAt;
        }

        public String getNotifyKey() { return notifyKey; }
        public String getOrderNo() { return orderNo; }
        public String getBoxNo() { return boxNo; }
        public Long getRelicId() { return relicId; }
        public Long getAgreementId() { return agreementId; }
        public String getSpecVersionNo() { return specVersionNo; }
        public String getPackType() { return packType; }
        public BigDecimal getActualWeightG() { return actualWeightG; }
        public LocalDateTime getConfirmedAt() { return confirmedAt; }
    }
}
