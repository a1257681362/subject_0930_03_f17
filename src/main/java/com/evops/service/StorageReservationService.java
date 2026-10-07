package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.config.MuseumStorageProperties;
import com.evops.dto.StorageReservationCreateRequest;
import com.evops.entity.StorageCell;
import com.evops.entity.StorageMoveOrder;
import com.evops.entity.StorageRack;
import com.evops.entity.StorageReservation;
import com.evops.entity.StorageStock;
import com.evops.mapper.StorageCellMapper;
import com.evops.mapper.StorageMoveOrderMapper;
import com.evops.mapper.StorageRackMapper;
import com.evops.mapper.StorageReservationMapper;
import com.evops.mapper.StorageStockMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.evops.constant.MuseumConstants.*;

/**
 * 库位预约与移动：搬入前必须预约目标格位。
 *
 * <p>关键规则：</p>
 * <ul>
 *   <li>请求流水号 {@code requestNo} 唯一：修复侧补传/补录/重试复用同一流水号，
 *       预约只形成一条可用结果，绝不产生第二条。</li>
 *   <li>同一格位同时只有一个在途（RESERVED/MOVING）预约：{@code hold_key} 稀疏唯一约束兜底。</li>
 *   <li>互斥设备（密集架）同一时段只允许一个操作：时间窗重叠即拒绝，架体行锁串行化并发。</li>
 *   <li>开工后不可取消；超时释放只作用于 RESERVED，MOVING 永不释放（见 Scheduler）。</li>
 *   <li>完成移动原子清原格、立目的格；移动单固化原位/目的位/箱号/操作人员快照。</li>
 * </ul>
 */
@Service
public class StorageReservationService {

    private final StorageReservationMapper reservationMapper;
    private final StorageMoveOrderMapper moveOrderMapper;
    private final StorageStockMapper stockMapper;
    private final StorageCellMapper cellMapper;
    private final StorageRackMapper rackMapper;
    private final StorageLocationService locationService;
    private final StorageLedgerService ledgerService;
    private final MuseumStorageProperties properties;

    public StorageReservationService(StorageReservationMapper reservationMapper,
                                     StorageMoveOrderMapper moveOrderMapper,
                                     StorageStockMapper stockMapper,
                                     StorageCellMapper cellMapper,
                                     StorageRackMapper rackMapper,
                                     StorageLocationService locationService,
                                     StorageLedgerService ledgerService,
                                     MuseumStorageProperties properties) {
        this.reservationMapper = reservationMapper;
        this.moveOrderMapper = moveOrderMapper;
        this.stockMapper = stockMapper;
        this.cellMapper = cellMapper;
        this.rackMapper = rackMapper;
        this.locationService = locationService;
        this.ledgerService = ledgerService;
        this.properties = properties;
    }

    /**
     * 预约目标格位。
     * 同一 requestNo 的补传/补录/重试命中已存在的同一条预约并原样返回，不重复写。
     */
    @Transactional
    public StorageReservation reserve(StorageReservationCreateRequest req) {
        // 幂等：凭请求流水号回读同一条可用结果
        StorageReservation existing = findByRequestNo(req.getRequestNo());
        if (existing != null) {
            return existing;
        }

        if (!req.getPlanStartTime().isBefore(req.getPlanEndTime())) {
            throw new IllegalArgumentException("计划开始时间必须早于计划结束时间");
        }
        StorageCell target = locationService.requireCell(req.getTargetCellId());
        if (!CELL_ACTIVE.equals(target.getStatus())) {
            throw new IllegalArgumentException("目标格位已停用，不能预约: " + target.getCellNo());
        }
        if (req.getWeightKg() != null
                && req.getWeightKg().compareTo(target.getLoadLimitKg()) > 0) {
            throw new IllegalArgumentException("箱体重量 " + req.getWeightKg()
                    + "kg 超过格位承重上限 " + target.getLoadLimitKg() + "kg");
        }
        if (req.getOriginCellId() != null) {
            StorageCell origin = locationService.requireCell(req.getOriginCellId());
            if (origin.getId().equals(target.getId())) {
                throw new IllegalArgumentException("原格位与目标格位不能相同");
            }
            // 库内移动：原格位当前必须确实存放该箱号
            Long boxAtOrigin = stockMapper.selectCount(new LambdaQueryWrapper<StorageStock>()
                    .eq(StorageStock::getCellId, origin.getId())
                    .eq(StorageStock::getBoxNo, req.getBoxNo()));
            if (boxAtOrigin == null || boxAtOrigin == 0) {
                throw new IllegalArgumentException("原格位 " + origin.getCellNo()
                        + " 当前未存放箱号 " + req.getBoxNo() + "，不能发起库内移动");
            }
        }

        // 密集架行锁：把同架体的并发预约串行化，再做占用与互斥判定
        rackMapper.lockRow(target.getRackId());

        // 行锁内二次回读：相同流水号的并发补传/补录/重试在锁等待后命中同一条结果
        StorageReservation racedRequest = findByRequestNo(req.getRequestNo());
        if (racedRequest != null) {
            return racedRequest;
        }
        if (reservationMapper.selectCount(new LambdaQueryWrapper<StorageReservation>()
                .eq(StorageReservation::getHoldKey, target.getId())) > 0) {
            throw new IllegalArgumentException("目标格位已有在途预约或移动: " + target.getCellNo());
        }
        if (stockMapper.selectCount(new LambdaQueryWrapper<StorageStock>()
                .eq(StorageStock::getCellId, target.getId())) > 0) {
            throw new IllegalArgumentException("目标格位已被占用: " + target.getCellNo());
        }
        // 互斥设备：同一密集架时间窗重叠的在途操作只允许一个
        if (countRackOverlap(target.getRackId(),
                req.getPlanStartTime(), req.getPlanEndTime(), null) > 0) {
            StorageRack rack = locationService.requireRack(target.getRackId());
            throw new IllegalArgumentException("互斥设备 " + rack.getEquipmentNo()
                    + " 在该时段已有预约/移动操作，同一时段只允许一个操作");
        }

        LocalDateTime now = LocalDateTime.now();
        long expireMinutes = req.getExpireMinutes() != null && req.getExpireMinutes() >= 0
                ? req.getExpireMinutes() : properties.getReservationExpireMinutes();

        StorageReservation reservation = new StorageReservation();
        reservation.setRequestNo(req.getRequestNo());
        reservation.setReservationNo(nextReservationNo());
        reservation.setAreaId(target.getAreaId());
        reservation.setRackId(target.getRackId());
        reservation.setTargetCellId(target.getId());
        reservation.setOriginCellId(req.getOriginCellId());
        reservation.setRelicId(req.getRelicId());
        reservation.setBoxNo(req.getBoxNo());
        reservation.setWeightKg(req.getWeightKg());
        reservation.setPlanStartTime(req.getPlanStartTime());
        reservation.setPlanEndTime(req.getPlanEndTime());
        reservation.setReservedBy(req.getReservedBy());
        reservation.setExpireAt(now.plusMinutes(expireMinutes));
        reservation.setHoldKey(target.getId());
        reservation.setStatus(RES_RESERVED);
        try {
            reservationMapper.insert(reservation);
        } catch (DuplicateKeyException e) {
            // 并发兜底：流水号重复（补传/补录/重试）或格位刚被别的在途预约持有
            StorageReservation raced = findByRequestNo(req.getRequestNo());
            if (raced != null) {
                return raced;
            }
            throw new IllegalArgumentException("目标格位已被其他在途预约占用: " + target.getCellNo());
        }

        ledgerService.append(target.getAreaId(), target.getRackId(), target.getId(),
                reservation.getId(), null, ACTION_RESERVE, reservation.getReservationNo(),
                "预约目标格位 " + target.getCellNo() + "，箱号 " + reservation.getBoxNo()
                        + "，预约人 " + reservation.getReservedBy()
                        + "，计划时段 " + req.getPlanStartTime() + "~" + req.getPlanEndTime()
                        + "，超时时刻 " + reservation.getExpireAt(), now);
        return reservation;
    }

    /**
     * 开工移动：RESERVED -> MOVING，登记操作人员并生成移动单。
     */
    @Transactional
    public StorageMoveOrder start(Long reservationId, String operator) {
        StorageReservation reservation = requireReservation(reservationId);

        // 幂等：已开工/已完成直接回原移动单（补传/重试不产生第二条）
        StorageMoveOrder existing = findMoveOrder(reservationId);
        if (existing != null) {
            return existing;
        }
        if (!RES_RESERVED.equals(reservation.getStatus())) {
            throw new IllegalArgumentException("预约状态为 " + reservation.getStatus() + "，不能开工");
        }
        if (!reservation.getExpireAt().isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException("预约已超时释放，不能开工，请重新预约");
        }

        StorageRack rack = locationService.requireRack(reservation.getRackId());
        rackMapper.lockRow(reservation.getRackId());
        // 开工时再查一次互斥设备：同架体若已有移动正在进行且时段重叠则拒绝
        if (countRackOverlap(reservation.getRackId(),
                reservation.getPlanStartTime(), reservation.getPlanEndTime(),
                reservation.getId()) > 0) {
            throw new IllegalArgumentException("互斥设备 " + rack.getEquipmentNo()
                    + " 正在进行时段重叠的移动操作，请错开时间");
        }

        LocalDateTime now = LocalDateTime.now();
        reservation.setStatus(RES_MOVING);
        reservation.setOperator(operator);
        reservation.setStartedAt(now);
        // hold_key 保持 = 目标格位：已开工格位不允许被超时释放、也不允许被他人预约
        reservationMapper.updateById(reservation);

        StorageCell target = locationService.requireCell(reservation.getTargetCellId());
        StorageMoveOrder order = new StorageMoveOrder();
        order.setOrderNo(nextMoveOrderNo());
        order.setReservationId(reservation.getId());
        order.setOriginCellId(reservation.getOriginCellId());
        order.setTargetCellId(target.getId());
        order.setTargetCellNo(target.getCellNo());
        order.setBoxNo(reservation.getBoxNo());
        order.setRelicId(reservation.getRelicId());
        order.setOperator(operator);
        order.setPlanStartTime(reservation.getPlanStartTime());
        order.setPlanEndTime(reservation.getPlanEndTime());
        order.setStartedAt(now);
        order.setStatus(MOVE_MOVING);
        if (reservation.getOriginCellId() != null) {
            order.setOriginCellNo(locationService.requireCell(reservation.getOriginCellId()).getCellNo());
        }
        moveOrderMapper.insert(order);

        ledgerService.append(reservation.getAreaId(), reservation.getRackId(),
                reservation.getTargetCellId(), reservation.getId(), order.getId(),
                ACTION_MOVE_START, order.getOrderNo(),
                "移动开工：箱号 " + order.getBoxNo()
                        + "，原位 " + (order.getOriginCellNo() == null ? "（新入库）" : order.getOriginCellNo())
                        + "，目的位 " + order.getTargetCellNo()
                        + "，操作人员 " + operator, now);
        return order;
    }

    /**
     * 完成移动：MOVING -> COMPLETED，原子清原格、立目的格。
     */
    @Transactional
    public StorageMoveOrder complete(Long reservationId) {
        StorageReservation reservation = requireReservation(reservationId);
        StorageMoveOrder order = findMoveOrder(reservationId);
        if (order == null) {
            throw new IllegalArgumentException("预约尚未开工，不能完成");
        }
        if (MOVE_COMPLETED.equals(order.getStatus())) {
            return order; // 幂等：重复完成不产生第二条
        }
        if (!RES_MOVING.equals(reservation.getStatus()) || !MOVE_MOVING.equals(order.getStatus())) {
            throw new IllegalArgumentException("移动单当前状态不能完成");
        }

        LocalDateTime now = LocalDateTime.now();
        // 原子转移库存：清原格（库内移动）、立目的格
        if (order.getOriginCellId() != null) {
            StorageStock originStock = stockMapper.selectOne(new LambdaQueryWrapper<StorageStock>()
                    .eq(StorageStock::getCellId, order.getOriginCellId()));
            if (originStock != null && order.getBoxNo().equals(originStock.getBoxNo())) {
                stockMapper.deleteById(originStock.getId());
            }
        }
        StorageStock targetStock = new StorageStock();
        targetStock.setCellId(order.getTargetCellId());
        targetStock.setBoxNo(order.getBoxNo());
        targetStock.setRelicId(order.getRelicId());
        targetStock.setReservationId(reservation.getId());
        targetStock.setInboundAt(now);
        try {
            stockMapper.insert(targetStock);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("目标格位已有箱体占用，移动无法落位: "
                    + order.getTargetCellNo());
        }

        reservation.setStatus(RES_COMPLETED);
        reservation.setFinishedAt(now);
        reservation.setHoldKey(null); // 终态释放格位持有键（格位转为由库存占用）
        reservationMapper.updateById(reservation);
        order.setStatus(MOVE_COMPLETED);
        order.setFinishedAt(now);
        moveOrderMapper.updateById(order);

        ledgerService.append(reservation.getAreaId(), reservation.getRackId(),
                reservation.getTargetCellId(), reservation.getId(), order.getId(),
                ACTION_MOVE_COMPLETE, order.getOrderNo(),
                "移动完成：箱号 " + order.getBoxNo()
                        + "，原位 " + (order.getOriginCellNo() == null ? "（新入库）" : order.getOriginCellNo())
                        + "，目的位 " + order.getTargetCellNo()
                        + "，操作人员 " + order.getOperator(), now);
        return order;
    }

    /**
     * 取消预约：仅 RESERVED 可取消；MOVING 已开工不可取消。
     */
    @Transactional
    public StorageReservation cancel(Long reservationId, String reason) {
        StorageReservation reservation = requireReservation(reservationId);
        if (RES_MOVING.equals(reservation.getStatus())) {
            throw new IllegalArgumentException("移动已开工，不能取消预约");
        }
        if (!RES_RESERVED.equals(reservation.getStatus())) {
            throw new IllegalArgumentException("预约状态为 " + reservation.getStatus() + "，不能取消");
        }
        reservation.setStatus(RES_CANCELLED);
        reservation.setHoldKey(null);
        reservation.setCancelReason(reason);
        reservationMapper.updateById(reservation);
        ledgerService.append(reservation.getAreaId(), reservation.getRackId(),
                reservation.getTargetCellId(), reservation.getId(), null,
                ACTION_RESERVE_CANCEL, reservation.getReservationNo(),
                "预约取消：箱号 " + reservation.getBoxNo()
                        + "，目的位 " + cellName(reservation.getTargetCellId())
                        + "，原因：" + reason, LocalDateTime.now());
        return reservation;
    }

    /**
     * 超时释放单条：只在仍为 RESERVED 且已过超时时刻时生效。
     * 由定时扫描器在独立事务中逐行调用；MOVING 不满足 WHERE，永不释放。
     * @return 是否实际释放
     */
    @Transactional
    public boolean releaseIfTimedOut(Long reservationId, LocalDateTime now) {
        int affected = reservationMapper.expireIfTimedOut(reservationId, now);
        if (affected == 0) {
            return false;
        }
        StorageReservation reservation = reservationMapper.selectById(reservationId);
        ledgerService.append(reservation.getAreaId(), reservation.getRackId(),
                reservation.getTargetCellId(), reservation.getId(), null,
                ACTION_RESERVE_EXPIRE, reservation.getReservationNo(),
                "预约超时自动释放：箱号 " + reservation.getBoxNo()
                        + "，目的位 " + cellName(reservation.getTargetCellId())
                        + "（超时时刻 " + reservation.getExpireAt() + "）", now);
        return true;
    }

    // ---- 查询 ----

    public StorageReservation findByRequestNo(String requestNo) {
        return reservationMapper.selectOne(new LambdaQueryWrapper<StorageReservation>()
                .eq(StorageReservation::getRequestNo, requestNo));
    }

    public StorageReservation requireReservation(Long id) {
        StorageReservation reservation = reservationMapper.selectById(id);
        if (reservation == null) {
            throw new IllegalArgumentException("预约不存在: id=" + id);
        }
        return reservation;
    }

    public StorageMoveOrder findMoveOrder(Long reservationId) {
        return moveOrderMapper.selectOne(new LambdaQueryWrapper<StorageMoveOrder>()
                .eq(StorageMoveOrder::getReservationId, reservationId));
    }

    public List<StorageReservation> listReservations(String status, String requestNo, Long cellId) {
        return reservationMapper.selectList(new LambdaQueryWrapper<StorageReservation>()
                .eq(status != null && !status.isEmpty(), StorageReservation::getStatus, status)
                .eq(requestNo != null && !requestNo.isEmpty(), StorageReservation::getRequestNo, requestNo)
                .eq(cellId != null, StorageReservation::getTargetCellId, cellId)
                .orderByDesc(StorageReservation::getId));
    }

    /** 预约详情：预约 + 移动单 + 台账 */
    public Map<String, Object> detail(Long id) {
        StorageReservation reservation = requireReservation(id);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reservation", reservation);
        detail.put("moveOrder", findMoveOrder(id));
        detail.put("ledger", ledgerService.listByReservation(id));
        return detail;
    }

    public List<StorageMoveOrder> listMoveOrders(Long rackId, String boxNo) {
        // rackId 经由预约/格位过滤较绕，移动单本身无 rack_id：按箱号过滤 + 内存中按架体过滤
        List<StorageMoveOrder> orders = moveOrderMapper.selectList(new LambdaQueryWrapper<StorageMoveOrder>()
                .eq(boxNo != null && !boxNo.isEmpty(), StorageMoveOrder::getBoxNo, boxNo)
                .orderByDesc(StorageMoveOrder::getId));
        if (rackId == null) {
            return orders;
        }
        List<StorageMoveOrder> filtered = new ArrayList<>();
        for (StorageMoveOrder order : orders) {
            StorageCell cell = cellMapper.selectById(order.getTargetCellId());
            if (cell != null && rackId.equals(cell.getRackId())) {
                filtered.add(order);
            }
        }
        return filtered;
    }

    /**
     * 库存查询，按馆员权限隐藏敏感位置：
     * WAREHOUSE_KEEPER 见全部精确位置；CURATOR 敏感格位保留行但位置脱敏（masked=true）；
     * VIEWER（含未声明角色）直接过滤掉敏感格位。
     */
    public List<Map<String, Object>> queryStock(String role, Long areaId) {
        boolean fullView = ROLE_WAREHOUSE_KEEPER.equals(role);
        boolean maskedView = ROLE_CURATOR.equals(role);
        // 其余角色（含 VIEWER/访客/未声明）一律按最低权限过滤敏感位置
        List<StorageStock> stocks = stockMapper.selectList(
                new LambdaQueryWrapper<StorageStock>().orderByAsc(StorageStock::getId));

        Map<Long, StorageCell> cellCache = new HashMap<>();
        Map<Long, String> areaNoCache = new HashMap<>();
        List<Map<String, Object>> result = new ArrayList<>();
        for (StorageStock stock : stocks) {
            StorageCell cell = cellCache.computeIfAbsent(stock.getCellId(), cellMapper::selectById);
            if (cell == null) {
                continue;
            }
            if (areaId != null && !areaId.equals(cell.getAreaId())) {
                continue;
            }
            boolean sensitive = cell.getSensitive() != null && cell.getSensitive() == 1;
            if (sensitive && !fullView && !maskedView) {
                continue; // 访客等低权限不可见敏感位置
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stockId", stock.getId());
            row.put("boxNo", stock.getBoxNo());
            row.put("relicId", stock.getRelicId());
            row.put("reservationId", stock.getReservationId());
            row.put("inboundAt", stock.getInboundAt());
            if (sensitive && maskedView) {
                // 保管员：保留库存事实，精确位置全部脱敏
                row.put("masked", true);
                row.put("locationName", "敏感库位");
                row.put("cellId", null);
                row.put("cellNo", null);
                row.put("rackId", null);
                row.put("rackNo", null);
                row.put("areaId", null);
                row.put("areaNo", null);
                row.put("shelfNo", null);
                row.put("colNo", null);
            } else {
                StorageRack rack = rackMapper.selectById(cell.getRackId());
                String areaNo = areaNoCache.computeIfAbsent(cell.getAreaId(),
                        aid -> locationService.requireArea(aid).getAreaNo());
                row.put("masked", false);
                row.put("cellId", cell.getId());
                row.put("cellNo", cell.getCellNo());
                row.put("rackId", cell.getRackId());
                row.put("rackNo", rack == null ? null : rack.getRackNo());
                row.put("areaId", cell.getAreaId());
                row.put("areaNo", areaNo);
                row.put("shelfNo", cell.getShelfNo());
                row.put("colNo", cell.getColNo());
                row.put("sensitive", sensitive);
            }
            result.add(row);
        }
        return result;
    }

    // ---- 内部 ----

    /** 列出已超时待释放的 RESERVED 预约，供定时扫描 */
    public List<StorageReservation> listDueReservations(LocalDateTime now) {
        return reservationMapper.selectList(new LambdaQueryWrapper<StorageReservation>()
                .eq(StorageReservation::getStatus, RES_RESERVED)
                .lt(StorageReservation::getExpireAt, now)
                .orderByAsc(StorageReservation::getId));
    }

    private long countRackOverlap(Long rackId, LocalDateTime start, LocalDateTime end, Long excludeId) {
        List<String> inFlight = Arrays.asList(RES_RESERVED, RES_MOVING);
        Long count = reservationMapper.selectCount(new LambdaQueryWrapper<StorageReservation>()
                .eq(StorageReservation::getRackId, rackId)
                .in(StorageReservation::getStatus, inFlight)
                .lt(StorageReservation::getPlanStartTime, end)
                .gt(StorageReservation::getPlanEndTime, start)
                .ne(excludeId != null, StorageReservation::getId, excludeId));
        return count == null ? 0 : count;
    }

    private String nextReservationNo() {
        return String.format("RS-%06d", reservationMapper.nextReservationSeq());
    }

    private String nextMoveOrderNo() {
        return String.format("MV-%06d", moveOrderMapper.nextMoveOrderSeq());
    }

    private String cellName(Long cellId) {
        StorageCell cell = cellMapper.selectById(cellId);
        return cell == null ? String.valueOf(cellId) : cell.getCellNo();
    }
}
