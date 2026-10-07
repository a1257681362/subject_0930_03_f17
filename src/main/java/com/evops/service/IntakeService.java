package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.constant.MuseumConstants;
import com.evops.dto.IntakeRequest;
import com.evops.dto.PhotoDto;
import com.evops.entity.Relic;
import com.evops.entity.RelicIntake;
import com.evops.entity.RelicPhoto;
import com.evops.mapper.RelicIntakeMapper;
import com.evops.mapper.RelicPhotoMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 入藏登记：关联照片、尺寸、库位。一件文物仅一条入藏记录。
 */
@Service
public class IntakeService {

    private final RelicIntakeMapper intakeMapper;
    private final RelicPhotoMapper photoMapper;
    private final RelicService relicService;
    private final LedgerService ledgerService;

    public IntakeService(RelicIntakeMapper intakeMapper,
                         RelicPhotoMapper photoMapper,
                         RelicService relicService,
                         LedgerService ledgerService) {
        this.intakeMapper = intakeMapper;
        this.photoMapper = photoMapper;
        this.relicService = relicService;
        this.ledgerService = ledgerService;
    }

    @Transactional
    public RelicIntake register(Long relicId, IntakeRequest req) {
        Relic relic = relicService.requireActiveRelic(relicId);

        RelicIntake existing = intakeMapper.selectOne(new LambdaQueryWrapper<RelicIntake>()
                .eq(RelicIntake::getRelicId, relicId));
        if (existing != null) {
            throw new IllegalArgumentException("该文物已办理入藏登记，不能重复入藏");
        }
        if (intakeMapper.selectCount(new LambdaQueryWrapper<RelicIntake>()
                .eq(RelicIntake::getIntakeNo, req.getIntakeNo())) > 0) {
            throw new IllegalArgumentException("入藏单号已存在: " + req.getIntakeNo());
        }

        LocalDateTime intakeTime = req.getIntakeTime() == null ? LocalDateTime.now() : req.getIntakeTime();

        RelicIntake intake = new RelicIntake();
        intake.setRelicId(relicId);
        intake.setIntakeNo(req.getIntakeNo());
        intake.setHeightMm(req.getHeightMm());
        intake.setWidthMm(req.getWidthMm());
        intake.setDepthMm(req.getDepthMm());
        intake.setWeightG(req.getWeightG());
        intake.setLocation(req.getLocation());
        intake.setIntakeTime(intakeTime);
        intake.setReceiver(req.getReceiver());
        intake.setConditionNote(req.getConditionNote());
        intake.setStatus(MuseumConstants.ACTIVE);
        intakeMapper.insert(intake);

        int sort = 1;
        for (PhotoDto photoDto : req.getPhotos()) {
            RelicPhoto photo = new RelicPhoto();
            photo.setIntakeId(intake.getId());
            photo.setRelicId(relicId);
            photo.setPhotoUrl(photoDto.getPhotoUrl());
            photo.setPhotoName(photoDto.getPhotoName());
            photo.setViewType(photoDto.getViewType());
            photo.setSortNo(photoDto.getSortNo() == null ? sort : photoDto.getSortNo());
            photoMapper.insert(photo);
            sort++;
        }

        ledgerService.append(relicId, null, null,
                MuseumConstants.ACTION_INTAKE, req.getIntakeNo(),
                "入藏登记，库位：" + req.getLocation()
                        + "，照片 " + req.getPhotos().size() + " 张",
                intakeTime);
        return intake;
    }

    public RelicIntake getByRelic(Long relicId) {
        return intakeMapper.selectOne(new LambdaQueryWrapper<RelicIntake>()
                .eq(RelicIntake::getRelicId, relicId));
    }

    public java.util.List<RelicPhoto> listPhotos(Long intakeId) {
        return photoMapper.selectList(new LambdaQueryWrapper<RelicPhoto>()
                .eq(RelicPhoto::getIntakeId, intakeId)
                .orderByAsc(RelicPhoto::getSortNo)
                .orderByAsc(RelicPhoto::getId));
    }
}
