package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.dto.PestCardDeployRequest;
import com.evops.dto.PestPointCreateRequest;
import com.evops.dto.PestPointMoveRequest;
import com.evops.entity.Hall;
import com.evops.entity.PestPointMove;
import com.evops.entity.PestTrapCard;
import com.evops.entity.PestTrapPoint;
import com.evops.entity.StorageArea;
import com.evops.mapper.PestPointMoveMapper;
import com.evops.mapper.PestTrapCardMapper;
import com.evops.mapper.PestTrapPointMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static com.evops.constant.MuseumConstants.*;

/**
 * 诱捕点位与诱捕片：
 * <ol>
 *   <li>点位设在展厅/库房，编号唯一，登记布放日期、坐标与责任区域；</li>
 *   <li>点位迁移写入 {@link PestPointMove} 保留前后坐标，点位行只保存当前坐标；</li>
 *   <li>更换诱捕片只把旧片置 REPLACED 并布放新片，旧片与其监测记录永不删除。</li>
 * </ol>
 */
@Service
public class PestPointService {

    private final PestTrapPointMapper pointMapper;
    private final PestPointMoveMapper moveMapper;
    private final PestTrapCardMapper cardMapper;
    private final HallService hallService;
    private final StorageLocationService storageLocationService;
    private final PestLedgerService ledgerService;

    public PestPointService(PestTrapPointMapper pointMapper,
                            PestPointMoveMapper moveMapper,
                            PestTrapCardMapper cardMapper,
                            HallService hallService,
                            StorageLocationService storageLocationService,
                            PestLedgerService ledgerService) {
        this.pointMapper = pointMapper;
        this.moveMapper = moveMapper;
        this.cardMapper = cardMapper;
        this.hallService = hallService;
        this.storageLocationService = storageLocationService;
        this.ledgerService = ledgerService;
    }

    // ================================================================
    // 点位建档 / 迁移 / 停用
    // ================================================================

    /** 点位建档：展厅/库房必须存在，编号唯一，登记布放日期/坐标/责任区域 */
    @Transactional
    public PestTrapPoint create(PestPointCreateRequest req) {
        if (pointMapper.selectCount(new LambdaQueryWrapper<PestTrapPoint>()
                .eq(PestTrapPoint::getPointNo, req.getPointNo())) > 0) {
            throw new IllegalArgumentException("点位编号已存在: " + req.getPointNo());
        }
        String zoneName = requireZoneName(req.getZoneType(), req.getZoneId());

        PestTrapPoint point = new PestTrapPoint();
        point.setPointNo(req.getPointNo());
        point.setZoneType(req.getZoneType());
        point.setZoneId(req.getZoneId());
        point.setZoneName(zoneName);
        point.setResponsibleArea(req.getResponsibleArea());
        point.setCoordX(req.getCoordX());
        point.setCoordY(req.getCoordY());
        point.setDeployedAt(req.getDeployedAt());
        point.setDeployedBy(req.getDeployedBy());
        point.setStatus(PEST_POINT_ACTIVE);
        pointMapper.insert(point);

        ledgerService.append(point.getId(), null, null, null, null,
                ACTION_PEST_POINT_CREATE, point.getPointNo(),
                "诱捕点位 " + point.getPointNo() + " 布放于" + zoneTypeName(req.getZoneType())
                        + "【" + zoneName + "】，责任区域 " + req.getResponsibleArea()
                        + "，坐标 (" + req.getCoordX() + ", " + req.getCoordY() + ")",
                req.getDeployedAt());
        return point;
    }

    /**
     * 点位迁移：写入迁移记录保留前后坐标，点位行更新为当前坐标。
     * 迁移记录只追加，历史坐标永不丢失。
     */
    @Transactional
    public PestPointMove move(Long pointId, PestPointMoveRequest req) {
        PestTrapPoint point = requirePoint(pointId);
        LocalDateTime movedAt = req.getMovedAt() == null ? LocalDateTime.now() : req.getMovedAt();

        PestPointMove move = new PestPointMove();
        move.setPointId(pointId);
        move.setFromX(point.getCoordX());
        move.setFromY(point.getCoordY());
        move.setToX(req.getToX());
        move.setToY(req.getToY());
        move.setReason(req.getReason());
        move.setMovedBy(req.getMovedBy());
        move.setMovedAt(movedAt);
        moveMapper.insert(move);

        point.setCoordX(req.getToX());
        point.setCoordY(req.getToY());
        pointMapper.updateById(point);

        ledgerService.append(pointId, null, null, null, null,
                ACTION_PEST_POINT_MOVE, point.getPointNo(),
                "点位 " + point.getPointNo() + " 迁移：(" + move.getFromX() + ", "
                        + move.getFromY() + ") → (" + req.getToX() + ", " + req.getToY() + ")"
                        + (req.getReason() == null ? "" : "，原因：" + req.getReason()),
                movedAt);
        return move;
    }

    /** 点位停用：只置状态，历史监测与迁移记录保留 */
    @Transactional
    public PestTrapPoint retire(Long pointId) {
        PestTrapPoint point = requirePoint(pointId);
        if (PEST_POINT_RETIRED.equals(point.getStatus())) {
            throw new IllegalArgumentException("点位" + point.getPointNo() + "已停用");
        }
        point.setStatus(PEST_POINT_RETIRED);
        pointMapper.updateById(point);
        ledgerService.append(pointId, null, null, null, null,
                ACTION_PEST_POINT_RETIRE, point.getPointNo(),
                "点位 " + point.getPointNo() + " 停用，历史监测与迁移记录保留",
                LocalDateTime.now());
        return point;
    }

    // ================================================================
    // 诱捕片布放 / 更换（旧记录不删除）
    // ================================================================

    /**
     * 布放/更换诱捕片：点位已有在布诱捕片时，旧片置 REPLACED（记录保留），
     * 新片在布；旧片上的监测记录永不删除。
     */
    @Transactional
    public PestTrapCard deployCard(Long pointId, PestCardDeployRequest req) {
        PestTrapPoint point = requirePoint(pointId);
        if (!PEST_POINT_ACTIVE.equals(point.getStatus())) {
            throw new IllegalArgumentException("点位" + point.getPointNo() + "已停用，不能布放诱捕片");
        }
        if (cardMapper.selectCount(new LambdaQueryWrapper<PestTrapCard>()
                .eq(PestTrapCard::getCardNo, req.getCardNo())) > 0) {
            throw new IllegalArgumentException("诱捕片编号已存在: " + req.getCardNo());
        }

        PestTrapCard old = activeCard(pointId);
        if (old != null) {
            // 更换：旧片只置状态，不删除；旧片监测记录保留可查
            old.setStatus(PEST_CARD_REPLACED);
            old.setReplacedAt(req.getDeployedAt());
            old.setReplacedBy(req.getDeployedBy());
            cardMapper.updateById(old);
        }

        PestTrapCard card = new PestTrapCard();
        card.setCardNo(req.getCardNo());
        card.setPointId(pointId);
        card.setDeployedAt(req.getDeployedAt());
        card.setDeployedBy(req.getDeployedBy());
        card.setStatus(PEST_CARD_ACTIVE);
        cardMapper.insert(card);

        ledgerService.append(pointId, null, null, null, null,
                old == null ? ACTION_PEST_CARD_DEPLOY : ACTION_PEST_CARD_REPLACE, card.getCardNo(),
                old == null
                        ? "点位 " + point.getPointNo() + " 布放诱捕片 " + card.getCardNo()
                        : "点位 " + point.getPointNo() + " 更换诱捕片：" + old.getCardNo()
                        + " 置 REPLACED（旧记录保留），新片 " + card.getCardNo() + " 在布",
                req.getDeployedAt());
        return card;
    }

    // ================================================================
    // 查询
    // ================================================================

    public PestTrapPoint requirePoint(Long pointId) {
        PestTrapPoint point = pointMapper.selectById(pointId);
        if (point == null) {
            throw new IllegalArgumentException("诱捕点位不存在: id=" + pointId);
        }
        return point;
    }

    public PestTrapCard requireCard(Long cardId) {
        PestTrapCard card = cardMapper.selectById(cardId);
        if (card == null) {
            throw new IllegalArgumentException("诱捕片不存在: id=" + cardId);
        }
        return card;
    }

    public PestTrapCard activeCard(Long pointId) {
        return cardMapper.selectOne(new LambdaQueryWrapper<PestTrapCard>()
                .eq(PestTrapCard::getPointId, pointId)
                .eq(PestTrapCard::getStatus, PEST_CARD_ACTIVE)
                .last("LIMIT 1"));
    }

    public List<PestTrapPoint> listPoints(String zoneType, Long zoneId, String status) {
        return pointMapper.selectList(new LambdaQueryWrapper<PestTrapPoint>()
                .eq(zoneType != null && !zoneType.isEmpty(), PestTrapPoint::getZoneType, zoneType)
                .eq(zoneId != null, PestTrapPoint::getZoneId, zoneId)
                .eq(status != null && !status.isEmpty(), PestTrapPoint::getStatus, status)
                .orderByAsc(PestTrapPoint::getId));
    }

    public List<PestTrapCard> listCards(Long pointId) {
        return cardMapper.selectList(new LambdaQueryWrapper<PestTrapCard>()
                .eq(PestTrapCard::getPointId, pointId)
                .orderByAsc(PestTrapCard::getId));
    }

    public List<PestPointMove> listMoves(Long pointId) {
        return moveMapper.selectList(new LambdaQueryWrapper<PestPointMove>()
                .eq(PestPointMove::getPointId, pointId)
                .orderByAsc(PestPointMove::getId));
    }

    // ================================================================
    // 辅助
    // ================================================================

    /** 区域存在性校验并取名称快照：展厅引用 t_hall，库房引用 t_storage_area */
    private String requireZoneName(String zoneType, Long zoneId) {
        if (PEST_ZONE_HALL.equals(zoneType)) {
            Hall hall = hallService.requireHall(zoneId);
            return hall.getName();
        }
        if (PEST_ZONE_STORAGE.equals(zoneType)) {
            StorageArea area = storageLocationService.requireArea(zoneId);
            return area.getName();
        }
        throw new IllegalArgumentException("区域类型只支持 HALL 展厅 / STORAGE 库房: " + zoneType);
    }

    static String zoneTypeName(String zoneType) {
        return PEST_ZONE_HALL.equals(zoneType) ? "展厅" : "库房";
    }
}
