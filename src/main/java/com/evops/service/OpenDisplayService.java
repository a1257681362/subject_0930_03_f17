package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.dto.OpenDisplaySetRequest;
import com.evops.entity.OpenDisplayGrant;
import com.evops.entity.OpenDisplayImage;
import com.evops.entity.Relic;
import com.evops.entity.RelicImage;
import com.evops.entity.RepairProject;
import com.evops.mapper.OpenDisplayGrantMapper;
import com.evops.mapper.OpenDisplayImageMapper;
import com.evops.mapper.RelicImageMapper;
import com.evops.mapper.RepairProjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.evops.constant.MuseumConstants.*;

/**
 * 开放展示标记：
 *   只有成果验收通过（PASSED）的项目才能设置；标记登记对外摘要与授权影像子集，
 *   公众页面只展示 ACTIVE 标记的授权内容；撤销只置 REVOKED 留痕，记录保留。
 */
@Service
public class OpenDisplayService {

    private final OpenDisplayGrantMapper grantMapper;
    private final OpenDisplayImageMapper grantImageMapper;
    private final RelicImageMapper imageMapper;
    private final RepairProjectMapper projectMapper;
    private final RelicService relicService;
    private final AcceptanceReviewService acceptanceReviewService;
    private final LedgerService ledgerService;

    public OpenDisplayService(OpenDisplayGrantMapper grantMapper,
                              OpenDisplayImageMapper grantImageMapper,
                              RelicImageMapper imageMapper,
                              RepairProjectMapper projectMapper,
                              RelicService relicService,
                              AcceptanceReviewService acceptanceReviewService,
                              LedgerService ledgerService) {
        this.grantMapper = grantMapper;
        this.grantImageMapper = grantImageMapper;
        this.imageMapper = imageMapper;
        this.projectMapper = projectMapper;
        this.relicService = relicService;
        this.acceptanceReviewService = acceptanceReviewService;
        this.ledgerService = ledgerService;
    }

    /**
     * 设置开放展示标记：须存在验收通过（PASSED）的成果报告；
     * 同一项目同时只允许一个生效中的标记；授权影像须属于该文物且有效。
     */
    @Transactional
    public OpenDisplayGrant setOpenDisplay(Long projectId, OpenDisplaySetRequest req) {
        RepairProject project = requireProject(projectId);
        com.evops.entity.AcceptReport passed = acceptanceReviewService.latestPassedReport(projectId);
        if (passed == null) {
            throw new IllegalArgumentException("成果验收未通过，不能设置开放展示标记: "
                    + project.getProjectNo());
        }
        Long active = grantMapper.selectCount(new LambdaQueryWrapper<OpenDisplayGrant>()
                .eq(OpenDisplayGrant::getProjectId, projectId)
                .eq(OpenDisplayGrant::getStatus, DISPLAY_GRANT_ACTIVE));
        if (active != null && active > 0) {
            throw new IllegalArgumentException("项目已存在生效中的开放展示标记，不能重复设置: "
                    + project.getProjectNo());
        }

        List<Long> imageIds = req.getImageIds().stream().distinct().collect(Collectors.toList());
        List<RelicImage> images = new ArrayList<>();
        for (Long imageId : imageIds) {
            RelicImage image = imageMapper.selectById(imageId);
            if (image == null) {
                throw new IllegalArgumentException("授权影像不存在: id=" + imageId);
            }
            if (!project.getRelicId().equals(image.getRelicId())) {
                throw new IllegalArgumentException("授权影像不属于该文物，不能开放: id=" + imageId);
            }
            if (!IMAGE_ACTIVE.equals(image.getStatus())) {
                throw new IllegalArgumentException("授权影像已作废，不能开放: id=" + imageId);
            }
            images.add(image);
        }

        LocalDateTime now = LocalDateTime.now();
        OpenDisplayGrant grant = new OpenDisplayGrant();
        grant.setProjectId(projectId);
        grant.setRelicId(project.getRelicId());
        grant.setReportId(passed.getId());
        grant.setSummary(req.getSummary().trim());
        grant.setStatus(DISPLAY_GRANT_ACTIVE);
        grant.setGrantedBy(req.getGrantedBy().trim());
        grant.setGrantedAt(now);
        grantMapper.insert(grant);

        for (RelicImage image : images) {
            OpenDisplayImage gi = new OpenDisplayImage();
            gi.setGrantId(grant.getId());
            gi.setImageId(image.getId());
            grantImageMapper.insert(gi);
        }

        ledgerService.append(project.getRelicId(), projectId, null,
                ACTION_OPEN_DISPLAY_SET, project.getProjectNo(),
                "开放展示标记设置（依据第" + passed.getRoundNo() + "轮验收通过报告），授权影像 "
                        + images.size() + " 张，设置人：" + grant.getGrantedBy(), now);
        return grant;
    }

    /** 撤销开放展示标记：只置 REVOKED 留痕，原记录保留 */
    @Transactional
    public OpenDisplayGrant revoke(Long grantId, String reason, String operator) {
        OpenDisplayGrant grant = requireGrant(grantId);
        if (!DISPLAY_GRANT_ACTIVE.equals(grant.getStatus())) {
            throw new IllegalArgumentException("开放展示标记已撤销，不能重复撤销: id=" + grantId);
        }
        LocalDateTime now = LocalDateTime.now();
        grant.setStatus(DISPLAY_GRANT_REVOKED);
        grant.setRevokeReason(reason);
        grant.setRevokedAt(now);
        grantMapper.updateById(grant);

        ledgerService.append(grant.getRelicId(), grant.getProjectId(), null,
                ACTION_OPEN_DISPLAY_REVOKE, "GRANT#" + grantId,
                "开放展示标记撤销，记录保留。原因：" + reason + "，操作人：" + operator, now);
        return grant;
    }

    /** 项目开放记录（含已撤销，管理端口径） */
    public List<Map<String, Object>> listByProject(Long projectId) {
        requireProject(projectId);
        List<OpenDisplayGrant> grants = grantMapper.selectList(
                new LambdaQueryWrapper<OpenDisplayGrant>()
                        .eq(OpenDisplayGrant::getProjectId, projectId)
                        .orderByDesc(OpenDisplayGrant::getId));
        List<Map<String, Object>> result = new ArrayList<>();
        for (OpenDisplayGrant grant : grants) {
            result.add(toManageView(grant));
        }
        return result;
    }

    /** 公众列表：仅生效中的标记，只暴露对外摘要与授权影像 */
    public List<Map<String, Object>> publicList() {
        List<OpenDisplayGrant> grants = grantMapper.selectList(
                new LambdaQueryWrapper<OpenDisplayGrant>()
                        .eq(OpenDisplayGrant::getStatus, DISPLAY_GRANT_ACTIVE)
                        .orderByAsc(OpenDisplayGrant::getId));
        List<Map<String, Object>> result = new ArrayList<>();
        for (OpenDisplayGrant grant : grants) {
            result.add(toPublicView(grant));
        }
        return result;
    }

    /** 公众详情：按文物查生效中的开放标记；未开放视为不存在 */
    public Map<String, Object> publicDetail(Long relicId) {
        OpenDisplayGrant grant = grantMapper.selectOne(new LambdaQueryWrapper<OpenDisplayGrant>()
                .eq(OpenDisplayGrant::getRelicId, relicId)
                .eq(OpenDisplayGrant::getStatus, DISPLAY_GRANT_ACTIVE)
                .last("LIMIT 1"));
        if (grant == null) {
            throw new IllegalArgumentException("该文物未开放展示: relicId=" + relicId);
        }
        return toPublicView(grant);
    }

    public OpenDisplayGrant requireGrant(Long grantId) {
        OpenDisplayGrant grant = grantMapper.selectById(grantId);
        if (grant == null) {
            throw new IllegalArgumentException("开放展示标记不存在: id=" + grantId);
        }
        return grant;
    }

    /** 项目当前生效中的开放标记（补修单关联依据），无则 null */
    public OpenDisplayGrant activeGrantOfProject(Long projectId) {
        return grantMapper.selectOne(new LambdaQueryWrapper<OpenDisplayGrant>()
                .eq(OpenDisplayGrant::getProjectId, projectId)
                .eq(OpenDisplayGrant::getStatus, DISPLAY_GRANT_ACTIVE)
                .last("LIMIT 1"));
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    /** 管理端视图：标记 + 授权影像明细（含内部字段） */
    private Map<String, Object> toManageView(OpenDisplayGrant grant) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("grant", grant);
        view.put("images", authorizedImages(grant.getId(), false));
        return view;
    }

    /** 公众视图：只暴露文物公开信息、对外摘要与授权影像（不含摄影者等内部字段） */
    private Map<String, Object> toPublicView(OpenDisplayGrant grant) {
        Relic relic = relicService.requireRelic(grant.getRelicId());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("relicId", relic.getId());
        view.put("relicNo", relic.getRelicNo());
        view.put("name", relic.getName());
        view.put("category", relic.getCategory());
        view.put("material", relic.getMaterial());
        view.put("era", relic.getEra());
        view.put("summary", grant.getSummary());
        view.put("images", authorizedImages(grant.getId(), true));
        return view;
    }

    /**
     * 授权影像：只取标记内授权且当前有效的影像。
     * publicOnly 时剔除摄影者等内部字段，只保留展示所需元数据。
     */
    private List<Map<String, Object>> authorizedImages(Long grantId, boolean publicOnly) {
        List<OpenDisplayImage> links = grantImageMapper.selectList(
                new LambdaQueryWrapper<OpenDisplayImage>()
                        .eq(OpenDisplayImage::getGrantId, grantId)
                        .orderByAsc(OpenDisplayImage::getId));
        List<Map<String, Object>> images = new ArrayList<>();
        for (OpenDisplayImage link : links) {
            RelicImage image = imageMapper.selectById(link.getImageId());
            if (image == null || !IMAGE_ACTIVE.equals(image.getStatus())) {
                // 授权后影像被作废的，公众与管理视图都不再展示（授权记录本身保留）
                continue;
            }
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("imageId", image.getId());
            view.put("viewType", image.getViewType());
            view.put("versionNo", image.getVersionNo());
            view.put("shotAt", image.getShotAt());
            if (!publicOnly) {
                view.put("photographer", image.getPhotographer());
                view.put("batchId", image.getBatchId());
            }
            images.add(view);
        }
        return images;
    }

    private RepairProject requireProject(Long projectId) {
        RepairProject project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new IllegalArgumentException("修复项目不存在: id=" + projectId);
        }
        return project;
    }
}
