package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.dto.StorageAreaCreateRequest;
import com.evops.dto.StorageCellCreateRequest;
import com.evops.dto.StorageRackCreateRequest;
import com.evops.entity.StorageArea;
import com.evops.entity.StorageCell;
import com.evops.entity.StorageRack;
import com.evops.mapper.StorageAreaMapper;
import com.evops.mapper.StorageCellMapper;
import com.evops.mapper.StorageRackMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static com.evops.constant.MuseumConstants.*;

/**
 * 库房库位维护：库区 / 密集架 / 格位三级建档与停用。
 * 库位按温区、防火等级、承重维护；停用只置状态，历史移动与台账永不删改。
 */
@Service
public class StorageLocationService {

    private static final List<String> ZONES = Arrays.asList(ZONE_CONST_TEMP, ZONE_COLD, ZONE_ORDINARY);
    private static final List<String> FIRE_RATINGS = Arrays.asList(FIRE_F1, FIRE_F2, FIRE_F3);

    private final StorageAreaMapper areaMapper;
    private final StorageRackMapper rackMapper;
    private final StorageCellMapper cellMapper;
    private final StorageLedgerService ledgerService;

    public StorageLocationService(StorageAreaMapper areaMapper,
                                  StorageRackMapper rackMapper,
                                  StorageCellMapper cellMapper,
                                  StorageLedgerService ledgerService) {
        this.areaMapper = areaMapper;
        this.rackMapper = rackMapper;
        this.cellMapper = cellMapper;
        this.ledgerService = ledgerService;
    }

    // ---- 库区 ----

    @Transactional
    public StorageArea createArea(StorageAreaCreateRequest req) {
        if (!ZONES.contains(req.getTempZone())) {
            throw new IllegalArgumentException("温区只能为 CONST_TEMP/COLD/ORDINARY");
        }
        if (!FIRE_RATINGS.contains(req.getFireRating())) {
            throw new IllegalArgumentException("防火等级只能为 F1/F2/F3");
        }
        if (areaMapper.selectCount(new LambdaQueryWrapper<StorageArea>()
                .eq(StorageArea::getAreaNo, req.getAreaNo())) > 0) {
            throw new IllegalArgumentException("库区编号已存在: " + req.getAreaNo());
        }
        StorageArea area = new StorageArea();
        area.setAreaNo(req.getAreaNo());
        area.setName(req.getName());
        area.setTempZone(req.getTempZone());
        area.setFireRating(req.getFireRating());
        area.setLoadCapacityKg(req.getLoadCapacityKg());
        area.setLocation(req.getLocation());
        area.setManager(req.getManager());
        area.setStatus(STORAGE_ACTIVE);
        areaMapper.insert(area);
        ledgerService.append(area.getId(), null, null, null, null,
                ACTION_CELL_MAINTAIN, area.getAreaNo(),
                "库区建档：" + area.getName() + "，温区 " + area.getTempZone()
                        + "，防火等级 " + area.getFireRating()
                        + "，承重 " + area.getLoadCapacityKg() + "kg", LocalDateTime.now());
        return area;
    }

    public StorageArea requireArea(Long id) {
        StorageArea area = areaMapper.selectById(id);
        if (area == null) {
            throw new IllegalArgumentException("库区不存在: id=" + id);
        }
        return area;
    }

    public List<StorageArea> listAreas(String status) {
        return areaMapper.selectList(new LambdaQueryWrapper<StorageArea>()
                .eq(status != null && !status.isEmpty(), StorageArea::getStatus, status)
                .orderByAsc(StorageArea::getId));
    }

    @Transactional
    public StorageArea closeArea(Long id) {
        StorageArea area = requireArea(id);
        area.setStatus(STORAGE_CLOSED);
        areaMapper.updateById(area);
        ledgerService.append(area.getId(), null, null, null, null,
                ACTION_CELL_MAINTAIN, area.getAreaNo(),
                "库区关闭停用：" + area.getName(), LocalDateTime.now());
        return area;
    }

    // ---- 密集架（互斥设备） ----

    @Transactional
    public StorageRack createRack(StorageRackCreateRequest req) {
        StorageArea area = requireArea(req.getAreaId());
        if (rackMapper.selectCount(new LambdaQueryWrapper<StorageRack>()
                .eq(StorageRack::getRackNo, req.getRackNo())) > 0) {
            throw new IllegalArgumentException("密集架编号已存在: " + req.getRackNo());
        }
        if (rackMapper.selectCount(new LambdaQueryWrapper<StorageRack>()
                .eq(StorageRack::getEquipmentNo, req.getEquipmentNo())) > 0) {
            throw new IllegalArgumentException("互斥设备编号已存在: " + req.getEquipmentNo());
        }
        StorageRack rack = new StorageRack();
        rack.setRackNo(req.getRackNo());
        rack.setAreaId(area.getId());
        rack.setEquipmentNo(req.getEquipmentNo());
        rack.setName(req.getName());
        rack.setAisle(req.getAisle());
        rack.setStatus(STORAGE_ACTIVE);
        rackMapper.insert(rack);
        ledgerService.append(area.getId(), rack.getId(), null, null, null,
                ACTION_CELL_MAINTAIN, rack.getRackNo(),
                "密集架建档（互斥设备 " + rack.getEquipmentNo() + "），库区：" + area.getAreaNo(),
                LocalDateTime.now());
        return rack;
    }

    public StorageRack requireRack(Long id) {
        StorageRack rack = rackMapper.selectById(id);
        if (rack == null) {
            throw new IllegalArgumentException("密集架不存在: id=" + id);
        }
        return rack;
    }

    public List<StorageRack> listRacks(Long areaId) {
        return rackMapper.selectList(new LambdaQueryWrapper<StorageRack>()
                .eq(areaId != null, StorageRack::getAreaId, areaId)
                .orderByAsc(StorageRack::getId));
    }

    // ---- 格位 ----

    @Transactional
    public StorageCell createCell(StorageCellCreateRequest req) {
        StorageRack rack = requireRack(req.getRackId());
        StorageArea area = requireArea(rack.getAreaId());
        if (cellMapper.selectCount(new LambdaQueryWrapper<StorageCell>()
                .eq(StorageCell::getCellNo, req.getCellNo())) > 0) {
            throw new IllegalArgumentException("格位编号已存在: " + req.getCellNo());
        }
        String tempZone = req.getTempZone() == null || req.getTempZone().isEmpty()
                ? area.getTempZone() : req.getTempZone();
        String fireRating = req.getFireRating() == null || req.getFireRating().isEmpty()
                ? area.getFireRating() : req.getFireRating();
        if (!ZONES.contains(tempZone)) {
            throw new IllegalArgumentException("温区只能为 CONST_TEMP/COLD/ORDINARY");
        }
        if (!FIRE_RATINGS.contains(fireRating)) {
            throw new IllegalArgumentException("防火等级只能为 F1/F2/F3");
        }
        if (req.getLoadLimitKg().compareTo(area.getLoadCapacityKg()) > 0) {
            throw new IllegalArgumentException("格位承重上限不能超过库区承重 "
                    + area.getLoadCapacityKg() + "kg");
        }
        StorageCell cell = new StorageCell();
        cell.setCellNo(req.getCellNo());
        cell.setAreaId(area.getId());
        cell.setRackId(rack.getId());
        cell.setShelfNo(req.getShelfNo());
        cell.setColNo(req.getColNo());
        cell.setTempZone(tempZone);
        cell.setFireRating(fireRating);
        cell.setLoadLimitKg(req.getLoadLimitKg());
        cell.setSensitive(Boolean.TRUE.equals(req.getSensitive()) ? 1 : 0);
        cell.setStatus(CELL_ACTIVE);
        cellMapper.insert(cell);
        ledgerService.append(area.getId(), rack.getId(), cell.getId(), null, null,
                ACTION_CELL_MAINTAIN, cell.getCellNo(),
                "格位建档：温区 " + tempZone + "，防火等级 " + fireRating
                        + "，承重上限 " + cell.getLoadLimitKg() + "kg"
                        + (cell.getSensitive() == 1 ? "，敏感位置" : ""),
                LocalDateTime.now());
        return cell;
    }

    public StorageCell requireCell(Long id) {
        StorageCell cell = cellMapper.selectById(id);
        if (cell == null) {
            throw new IllegalArgumentException("格位不存在: id=" + id);
        }
        return cell;
    }

    public List<StorageCell> listCells(Long areaId, Long rackId) {
        return cellMapper.selectList(new LambdaQueryWrapper<StorageCell>()
                .eq(areaId != null, StorageCell::getAreaId, areaId)
                .eq(rackId != null, StorageCell::getRackId, rackId)
                .orderByAsc(StorageCell::getId));
    }

    @Transactional
    public StorageCell disableCell(Long id) {
        StorageCell cell = requireCell(id);
        cell.setStatus(CELL_DISABLED);
        cellMapper.updateById(cell);
        ledgerService.append(cell.getAreaId(), cell.getRackId(), cell.getId(), null, null,
                ACTION_CELL_MAINTAIN, cell.getCellNo(),
                "格位停用：" + cell.getCellNo(), LocalDateTime.now());
        return cell;
    }
}
