package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.dto.HallCreateRequest;
import com.evops.dto.HallRelicRequest;
import com.evops.entity.Hall;
import com.evops.entity.HallRelic;
import com.evops.entity.InvDiffOrder;
import com.evops.entity.InvTask;
import com.evops.entity.Relic;
import com.evops.mapper.HallMapper;
import com.evops.mapper.HallRelicMapper;
import com.evops.mapper.InvDiffOrderMapper;
import com.evops.mapper.InvTaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static com.evops.constant.MuseumConstants.*;

/**
 * 展厅与陈展：展厅建档、文物上展/撤展。
 * 巡检任务按展厅生成；撤展文物不再采样，但历史读数/事件保留。
 */
@Service
public class HallService {

    private final HallMapper hallMapper;
    private final HallRelicMapper hallRelicMapper;
    private final RelicService relicService;
    private final InvTaskMapper invTaskMapper;
    private final InvDiffOrderMapper invDiffOrderMapper;
    private final InvLedgerService invLedgerService;

    public HallService(HallMapper hallMapper,
                       HallRelicMapper hallRelicMapper,
                       RelicService relicService,
                       InvTaskMapper invTaskMapper,
                       InvDiffOrderMapper invDiffOrderMapper,
                       InvLedgerService invLedgerService) {
        this.hallMapper = hallMapper;
        this.hallRelicMapper = hallRelicMapper;
        this.relicService = relicService;
        this.invTaskMapper = invTaskMapper;
        this.invDiffOrderMapper = invDiffOrderMapper;
        this.invLedgerService = invLedgerService;
    }

    @Transactional
    public Hall create(HallCreateRequest req) {
        if (hallMapper.selectCount(new LambdaQueryWrapper<Hall>()
                .eq(Hall::getHallNo, req.getHallNo())) > 0) {
            throw new IllegalArgumentException("展厅编号已存在: " + req.getHallNo());
        }
        Hall hall = new Hall();
        hall.setHallNo(req.getHallNo());
        hall.setName(req.getName());
        hall.setLocation(req.getLocation());
        hall.setCustodian(req.getCustodian());
        hall.setStatus(HALL_ACTIVE);
        hallMapper.insert(hall);
        return hall;
    }

    public Hall requireHall(Long hallId) {
        Hall hall = hallMapper.selectById(hallId);
        if (hall == null) {
            throw new IllegalArgumentException("展厅不存在: id=" + hallId);
        }
        return hall;
    }

    public List<Hall> listHalls(String status) {
        return hallMapper.selectList(new LambdaQueryWrapper<Hall>()
                .eq(status != null && !status.isEmpty(), Hall::getStatus, status)
                .orderByAsc(Hall::getId));
    }

    /** 文物上展厅陈展：材质取档案快照，按点位归组采样 */
    @Transactional
    public HallRelic display(Long hallId, HallRelicRequest req) {
        Hall hall = requireHall(hallId);
        if (!HALL_ACTIVE.equals(hall.getStatus())) {
            throw new IllegalArgumentException("展厅已关闭，不能继续布展: " + hall.getHallNo());
        }
        Relic relic = relicService.requireRelic(req.getRelicId());
        if (!ACTIVE.equals(relic.getStatus())) {
            throw new IllegalArgumentException("文物已撤销，不能陈展: " + relic.getRelicNo());
        }
        HallRelic existing = hallRelicMapper.selectOne(new LambdaQueryWrapper<HallRelic>()
                .eq(HallRelic::getHallId, hallId)
                .eq(HallRelic::getRelicId, req.getRelicId()));
        if (existing != null) {
            if (DISPLAY_ON.equals(existing.getStatus())) {
                throw new IllegalArgumentException("文物已在该展厅陈展中: " + relic.getRelicNo());
            }
            existing.setPointCode(req.getPointCode());
            existing.setDisplayNo(req.getDisplayNo());
            existing.setBoxNo(req.getBoxNo());
            existing.setMaterial(relic.getMaterial());
            existing.setStatus(DISPLAY_ON);
            hallRelicMapper.updateById(existing);
            return existing;
        }
        HallRelic hallRelic = new HallRelic();
        hallRelic.setHallId(hallId);
        hallRelic.setRelicId(req.getRelicId());
        hallRelic.setMaterial(relic.getMaterial());
        hallRelic.setPointCode(req.getPointCode());
        hallRelic.setDisplayNo(req.getDisplayNo());
        hallRelic.setBoxNo(req.getBoxNo());
        hallRelic.setStatus(DISPLAY_ON);
        hallRelicMapper.insert(hallRelic);
        return hallRelic;
    }

    /** 撤展：只置状态，历史读数与事件保留可查 */
    @Transactional
    public HallRelic withdraw(Long hallId, Long relicId) {
        requireHall(hallId);
        HallRelic hallRelic = hallRelicMapper.selectOne(new LambdaQueryWrapper<HallRelic>()
                .eq(HallRelic::getHallId, hallId)
                .eq(HallRelic::getRelicId, relicId));
        if (hallRelic == null) {
            throw new IllegalArgumentException("该文物未在此展厅陈展: relicId=" + relicId);
        }
        if (!DISPLAY_ON.equals(hallRelic.getStatus())) {
            throw new IllegalArgumentException("文物非展出中状态，不能撤展");
        }
        hallRelic.setStatus(DISPLAY_WITHDRAWN);
        hallRelicMapper.updateById(hallRelic);
        return hallRelic;
    }

    public List<HallRelic> listOnDisplay(Long hallId) {
        return hallRelicMapper.selectList(new LambdaQueryWrapper<HallRelic>()
                .eq(HallRelic::getHallId, hallId)
                .eq(HallRelic::getStatus, DISPLAY_ON)
                .orderByAsc(HallRelic::getPointCode)
                .orderByAsc(HallRelic::getId));
    }

    /**
     * 关闭展厅（展览）：复核完成前不能关闭展览——
     * 存在清点中的任务或未复核完成的差异单时一律拒绝；关闭只置状态，历史保留。
     */
    @Transactional
    public Hall close(Long hallId, String closedBy) {
        Hall hall = requireHall(hallId);
        if (!HALL_ACTIVE.equals(hall.getStatus())) {
            throw new IllegalArgumentException("展厅已关闭，请勿重复关闭: " + hall.getHallNo());
        }
        Long openTaskCount = invTaskMapper.selectCount(new LambdaQueryWrapper<InvTask>()
                .eq(InvTask::getHallId, hallId)
                .eq(InvTask::getStatus, INV_TASK_OPEN));
        if (openTaskCount != null && openTaskCount > 0) {
            throw new IllegalArgumentException("展厅 " + hall.getHallNo()
                    + " 存在清点中的任务，复核完成前不能关闭展览");
        }
        List<Long> taskIds = invTaskMapper.selectList(new LambdaQueryWrapper<InvTask>()
                        .eq(InvTask::getHallId, hallId))
                .stream().map(InvTask::getId).collect(java.util.stream.Collectors.toList());
        if (!taskIds.isEmpty()) {
            Long openDiffCount = invDiffOrderMapper.selectCount(new LambdaQueryWrapper<InvDiffOrder>()
                    .in(InvDiffOrder::getTaskId, taskIds)
                    .eq(InvDiffOrder::getStatus, INV_DIFF_OPEN));
            if (openDiffCount != null && openDiffCount > 0) {
                throw new IllegalArgumentException("展厅 " + hall.getHallNo()
                        + " 存在未复核完成的清点差异单，复核完成前不能关闭展览");
            }
        }
        hall.setStatus(HALL_CLOSED);
        hallMapper.updateById(hall);
        invLedgerService.append(hallId, null, null, ACTION_INV_HALL_CLOSE, hall.getHallNo(),
                "展厅关闭（展览结束），清点差异均已复核完成，关闭人："
                        + (closedBy == null ? "未登记" : closedBy),
                LocalDateTime.now());
        return hall;
    }
}
