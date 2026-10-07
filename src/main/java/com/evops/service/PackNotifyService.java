package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.evops.entity.PackNotifyOutbox;
import com.evops.entity.PackOrder;
import com.evops.gateway.MuseumRepairClient;
import com.evops.gateway.MuseumRepairUnavailableException;
import com.evops.mapper.PackLedgerMapper;
import com.evops.mapper.PackNotifyOutboxMapper;
import com.evops.mapper.PackOrderMapper;
import com.evops.entity.PackLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

import static com.evops.constant.MuseumConstants.*;

/**
 * 装箱确认外部通知投递：行级认领 + 状态机 PENDING → SENDING → SENT。
 *
 * <ul>
 *   <li>外部修复依赖无响应时抛 {@link MuseumRepairUnavailableException}，
 *       认领释放回 PENDING——调用方不得假装成功，装箱单停留 NOTIFY_PENDING；</li>
 *   <li>条件 UPDATE 认领把手动重试、定时补偿、重启恢复串行化，SENT 永不重投，
 *       恢复后对同一装箱单恰好通知一次。</li>
 * </ul>
 */
@Service
public class PackNotifyService {

    private static final Logger log = LoggerFactory.getLogger(PackNotifyService.class);

    private final PackNotifyOutboxMapper outboxMapper;
    private final PackOrderMapper orderMapper;
    private final PackLedgerMapper ledgerMapper;
    private final MuseumRepairClient repairClient;
    private final TransactionTemplate tx;

    public PackNotifyService(PackNotifyOutboxMapper outboxMapper,
                             PackOrderMapper orderMapper,
                             PackLedgerMapper ledgerMapper,
                             MuseumRepairClient repairClient,
                             PlatformTransactionManager transactionManager) {
        this.outboxMapper = outboxMapper;
        this.orderMapper = orderMapper;
        this.ledgerMapper = ledgerMapper;
        this.repairClient = repairClient;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * 投递某装箱单的装箱确认通知。
     *
     * @return true 已送达（或此前已送达）；外部无响应时抛异常而非返回 false
     * @throws MuseumRepairUnavailableException 依赖无响应，装箱单仍停留待确认
     */
    public boolean deliverOrder(Long orderId) {
        PackNotifyOutbox outbox = outboxMapper.selectOne(new LambdaQueryWrapper<PackNotifyOutbox>()
                .eq(PackNotifyOutbox::getOrderId, orderId)
                .last("LIMIT 1"));
        if (outbox == null) {
            throw new IllegalArgumentException("装箱单外部通知记录不存在: orderId=" + orderId);
        }
        if (NOTIFY_SENT.equals(outbox.getStatus())) {
            return true;
        }
        // 行级认领：只有一个调用者能把 PENDING 改成 SENDING
        int claimed = outboxMapper.claim(outbox.getId());
        if (claimed == 0) {
            PackNotifyOutbox now = outboxMapper.selectById(outbox.getId());
            if (now != null && NOTIFY_SENT.equals(now.getStatus())) {
                return true;
            }
            throw new MuseumRepairUnavailableException(
                    "装箱确认通知已有投递在途，本次不重复通知: " + outbox.getNotifyKey());
        }

        PackOrder order = orderMapper.selectById(orderId);
        MuseumRepairClient.PackConfirmNotice notice = new MuseumRepairClient.PackConfirmNotice(
                outbox.getNotifyKey(), order.getOrderNo(), order.getBoxNo(), order.getRelicId(),
                order.getAgreementId(), order.getSpecVersionNo(), order.getPackType(),
                order.getActualWeightG(), LocalDateTime.now());

        String receipt;
        try {
            receipt = repairClient.notifyPackConfirmed(notice);
        } catch (MuseumRepairUnavailableException e) {
            releaseToPending(outbox.getId(), e.getMessage());
            throw e;
        }
        markSent(outbox.getId(), orderId, outbox.getNotifyKey(), receipt);
        return true;
    }

    /** 外部无响应：释放认领回 PENDING 并记录错误，等待恢复后补偿（不推进装箱单） */
    public void releaseToPending(Long outboxId, String error) {
        tx.executeWithoutResult(status -> outboxMapper.update(null,
                new LambdaUpdateWrapper<PackNotifyOutbox>()
                        .eq(PackNotifyOutbox::getId, outboxId)
                        .eq(PackNotifyOutbox::getStatus, NOTIFY_SENDING)
                        .set(PackNotifyOutbox::getStatus, NOTIFY_PENDING)
                        .set(PackNotifyOutbox::getLastError, truncate(error))));
    }

    /** 送达成功：通知置 SENT、装箱单 PACKED、台账留痕（编程式事务原子提交） */
    public void markSent(Long outboxId, Long orderId, String notifyKey, String receipt) {
        tx.executeWithoutResult(status -> {
            LocalDateTime now = LocalDateTime.now();
            outboxMapper.update(null, new LambdaUpdateWrapper<PackNotifyOutbox>()
                    .eq(PackNotifyOutbox::getId, outboxId)
                    .set(PackNotifyOutbox::getStatus, NOTIFY_SENT)
                    .set(PackNotifyOutbox::getSentAt, now)
                    .set(PackNotifyOutbox::getLastError, null));
            orderMapper.update(null, new LambdaUpdateWrapper<PackOrder>()
                    .eq(PackOrder::getId, orderId)
                    .eq(PackOrder::getStatus, PACK_ORDER_NOTIFY_PENDING)
                    .set(PackOrder::getStatus, PACK_ORDER_PACKED));
            PackOrder order = orderMapper.selectById(orderId);
            appendLedger(order, ACTION_PACK_NOTIFY_SENT, order.getOrderNo(),
                    "装箱确认通知已送达外部修复依赖，通知键 " + notifyKey
                            + "（幂等：恢复/重试不重复通知），回执：" + truncate(receipt), now);
        });
    }

    /** 崩溃在 SENDING 的孤儿行：定时/手动补偿先重置为 PENDING 再投递 */
    public void resetSendingOrphans() {
        outboxMapper.update(null, new LambdaUpdateWrapper<PackNotifyOutbox>()
                .eq(PackNotifyOutbox::getStatus, NOTIFY_SENDING)
                .set(PackNotifyOutbox::getStatus, NOTIFY_PENDING)
                .set(PackNotifyOutbox::getLastError, "投递进程中断，重置待补偿"));
    }

    private void appendLedger(PackOrder order, String action, String bizNo,
                              String summary, LocalDateTime at) {
        PackLedger ledger = new PackLedger();
        ledger.setRelicId(order.getRelicId());
        ledger.setOrderId(order.getId());
        ledger.setSpecVersionId(order.getSpecVersionId());
        ledger.setSpecVersionNo(order.getSpecVersionNo());
        ledger.setAction(action);
        ledger.setBizNo(bizNo);
        ledger.setSummary(summary);
        ledger.setOccurredAt(at);
        ledgerMapper.insert(ledger);
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 480 ? s : s.substring(0, 480);
    }
}
