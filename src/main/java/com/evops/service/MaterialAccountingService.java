package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.config.MuseumMaterialProperties;
import com.evops.dto.MaterialCreateRequest;
import com.evops.dto.MaterialInboundRequest;
import com.evops.dto.MaterialReturnRequest;
import com.evops.dto.MaterialUsageRequest;
import com.evops.dto.UsageLineRequest;
import com.evops.entity.Material;
import com.evops.entity.MaterialBatch;
import com.evops.entity.MaterialInbound;
import com.evops.entity.MaterialReturn;
import com.evops.entity.MaterialReview;
import com.evops.entity.MaterialUsage;
import com.evops.entity.MaterialUsageLine;
import com.evops.entity.RepairProject;
import com.evops.mapper.MaterialBatchMapper;
import com.evops.mapper.MaterialInboundMapper;
import com.evops.mapper.MaterialMapper;
import com.evops.mapper.MaterialReturnMapper;
import com.evops.mapper.MaterialReviewMapper;
import com.evops.mapper.MaterialUsageLineMapper;
import com.evops.mapper.MaterialUsageMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.evops.constant.MuseumConstants.*;

/**
 * 修复材料核算：颜料/黏合剂/保护剂按批号入库，修复工单领用记用量、部位、操作者，
 * 过期批号禁领，退料关联原领用单，计划/实际用量超阈值触发差异复核。
 *
 * <p>库存按批号行管理，扣减/加回均为条件 UPDATE，与领用明细在同一事务内提交，
 * 保证库存与修复进度一致；明细行固化材料名称/批号/类别/单位/操作者快照，
 * 材料目录或批号之后变更不影响历史工单。</p>
 */
@Service
public class MaterialAccountingService {

    private static final List<String> CATEGORIES =
            Arrays.asList(MATERIAL_PIGMENT, MATERIAL_ADHESIVE, MATERIAL_PROTECTANT);
    private static final int VARIANCE_SCALE = 4;

    private final MaterialMapper materialMapper;
    private final MaterialBatchMapper batchMapper;
    private final MaterialInboundMapper inboundMapper;
    private final MaterialUsageMapper usageMapper;
    private final MaterialUsageLineMapper lineMapper;
    private final MaterialReturnMapper returnMapper;
    private final MaterialReviewMapper reviewMapper;
    private final RepairService repairService;
    private final LedgerService ledgerService;
    private final MuseumMaterialProperties properties;

    public MaterialAccountingService(MaterialMapper materialMapper,
                                     MaterialBatchMapper batchMapper,
                                     MaterialInboundMapper inboundMapper,
                                     MaterialUsageMapper usageMapper,
                                     MaterialUsageLineMapper lineMapper,
                                     MaterialReturnMapper returnMapper,
                                     MaterialReviewMapper reviewMapper,
                                     RepairService repairService,
                                     LedgerService ledgerService,
                                     MuseumMaterialProperties properties) {
        this.materialMapper = materialMapper;
        this.batchMapper = batchMapper;
        this.inboundMapper = inboundMapper;
        this.usageMapper = usageMapper;
        this.lineMapper = lineMapper;
        this.returnMapper = returnMapper;
        this.reviewMapper = reviewMapper;
        this.repairService = repairService;
        this.ledgerService = ledgerService;
        this.properties = properties;
    }

    // ------------------------------------------------------------------
    // 材料目录
    // ------------------------------------------------------------------

    @Transactional
    public Material createMaterial(MaterialCreateRequest req) {
        if (!CATEGORIES.contains(req.getCategory())) {
            throw new IllegalArgumentException("材料类别只能为 PIGMENT/ADHESIVE/PROTECTANT");
        }
        if (materialMapper.selectCount(new LambdaQueryWrapper<Material>()
                .eq(Material::getMaterialNo, req.getMaterialNo())) > 0) {
            throw new IllegalArgumentException("材料编号已存在: " + req.getMaterialNo());
        }
        Material material = new Material();
        material.setMaterialNo(req.getMaterialNo());
        material.setName(req.getName());
        material.setCategory(req.getCategory());
        material.setUnit(req.getUnit());
        material.setSpec(req.getSpec());
        material.setStatus(MATERIAL_ACTIVE);
        materialMapper.insert(material);
        return material;
    }

    public List<Material> listMaterials(String category) {
        LambdaQueryWrapper<Material> wrapper = new LambdaQueryWrapper<Material>()
                .orderByAsc(Material::getCategory)
                .orderByAsc(Material::getId);
        if (category != null && !category.isEmpty()) {
            wrapper.eq(Material::getCategory, category);
        }
        return materialMapper.selectList(wrapper);
    }

    public Material requireMaterialByNo(String materialNo) {
        Material material = materialMapper.selectOne(new LambdaQueryWrapper<Material>()
                .eq(Material::getMaterialNo, materialNo));
        if (material == null) {
            throw new IllegalArgumentException("材料不存在: " + materialNo);
        }
        return material;
    }

    // ------------------------------------------------------------------
    // 批号入库
    // ------------------------------------------------------------------

    /**
     * 按批号入库：批号全库唯一；同材料同批号再次入库累加库存并追加入库流水。
     * 入库本身不拦截临期/过期批号（允许登记），但过期批号不能被领用。
     */
    @Transactional
    public MaterialBatch inbound(MaterialInboundRequest req) {
        Material material = requireMaterialByNo(req.getMaterialNo());
        LocalDateTime now = LocalDateTime.now();
        BigDecimal qty = req.getQty();
        if (qty == null || qty.signum() <= 0) {
            throw new IllegalArgumentException("入库数量必须大于 0");
        }
        if (req.getProducedDate() != null && req.getExpireDate() != null
                && req.getExpireDate().isBefore(req.getProducedDate())) {
            throw new IllegalArgumentException("到期日期不能早于生产日期");
        }

        MaterialBatch batch = batchMapper.selectOne(new LambdaQueryWrapper<MaterialBatch>()
                .eq(MaterialBatch::getBatchNo, req.getBatchNo()));
        if (batch == null) {
            batch = new MaterialBatch();
            batch.setMaterialId(material.getId());
            batch.setBatchNo(req.getBatchNo());
            batch.setStockQty(qty);
            batch.setInboundQty(qty);
            batch.setProducedDate(req.getProducedDate());
            batch.setExpireDate(req.getExpireDate());
            batch.setSupplier(req.getSupplier());
            batch.setInboundAt(now);
            batch.setInboundBy(req.getInboundBy());
            batch.setStatus(BATCH_IN_STOCK);
            batchMapper.insert(batch);
        } else {
            if (!batch.getMaterialId().equals(material.getId())) {
                throw new IllegalArgumentException("批号已属于其他材料，不能重复入库: " + req.getBatchNo());
            }
            if (BATCH_DISPOSED.equals(batch.getStatus())) {
                throw new IllegalArgumentException("批号已报废，不能再入库: " + req.getBatchNo());
            }
            batch.setStockQty(nz(batch.getStockQty()).add(qty));
            batch.setInboundQty(nz(batch.getInboundQty()).add(qty));
            if (BATCH_EXHAUSTED.equals(batch.getStatus())) {
                // 追加入库使报废以外的用尽批号重新在库
                batch.setStatus(BATCH_IN_STOCK);
            }
            batchMapper.updateById(batch);
        }

        MaterialInbound inbound = new MaterialInbound();
        inbound.setBatchId(batch.getId());
        inbound.setMaterialId(material.getId());
        inbound.setQty(qty);
        inbound.setSupplier(req.getSupplier());
        inbound.setInboundAt(now);
        inbound.setInboundBy(req.getInboundBy());
        inboundMapper.insert(inbound);
        // 入库不绑定具体文物，台账表 relic_id 非空，故入库事实落在 t_material_inbound 流水
        return batch;
    }

    public MaterialBatch requireBatchByNo(String batchNo) {
        MaterialBatch batch = batchMapper.selectOne(new LambdaQueryWrapper<MaterialBatch>()
                .eq(MaterialBatch::getBatchNo, batchNo));
        if (batch == null) {
            throw new IllegalArgumentException("材料批号不存在: " + batchNo);
        }
        return batch;
    }

    public List<MaterialBatch> listBatches(Long materialId, Boolean usableOnly) {
        LambdaQueryWrapper<MaterialBatch> wrapper = new LambdaQueryWrapper<MaterialBatch>()
                .orderByDesc(MaterialBatch::getId);
        if (materialId != null) {
            wrapper.eq(MaterialBatch::getMaterialId, materialId);
        }
        List<MaterialBatch> batches = batchMapper.selectList(wrapper);
        if (Boolean.TRUE.equals(usableOnly)) {
            LocalDate today = LocalDate.now();
            batches.removeIf(b -> !isUsable(b, today));
        }
        return batches;
    }

    /** 批号可领用：在库、未过期（未设置到期日视为长期有效） */
    public boolean isUsable(MaterialBatch batch, LocalDate today) {
        if (!BATCH_IN_STOCK.equals(batch.getStatus())) {
            return false;
        }
        return batch.getExpireDate() == null || !batch.getExpireDate().isBefore(today);
    }

    // ------------------------------------------------------------------
    // 工单领用
    // ------------------------------------------------------------------

    /**
     * 工单领料：仅修复中（REPAIRING）项目可领用；逐行校验批号在库、未过期、库存充足，
     * 库存条件 UPDATE 与明细写入同一事务，任一行失败整体回滚。
     * 超阈值差异行置 PENDING，由差异复核扫描器落复核单（本事务尾部同步扫描一次）。
     */
    @Transactional
    public MaterialUsage issue(MaterialUsageRequest req) {
        RepairProject project = repairService.requireProject(req.getProjectId());
        if (!PROJECT_REPAIRING.equals(project.getStatus())) {
            throw new IllegalArgumentException(
                    "项目不在修复中，不能领用材料: " + project.getProjectNo() + "（" + project.getStatus() + "）");
        }
        if (usageMapper.selectCount(new LambdaQueryWrapper<MaterialUsage>()
                .eq(MaterialUsage::getUsageNo, req.getUsageNo())) > 0) {
            throw new IllegalArgumentException("领用单号已存在: " + req.getUsageNo());
        }
        if (req.getLines() == null || req.getLines().isEmpty()) {
            throw new IllegalArgumentException("领用明细至少一行");
        }

        LocalDateTime now = req.getUsageAt() == null ? LocalDateTime.now() : req.getUsageAt();
        LocalDate today = now.toLocalDate();
        BigDecimal threshold = properties.getVarianceThresholdPct();

        MaterialUsage usage = new MaterialUsage();
        usage.setUsageNo(req.getUsageNo());
        usage.setProjectId(project.getId());
        usage.setRelicId(project.getRelicId());
        usage.setUsageAt(now);
        usage.setStatus(USAGE_ISSUED);
        usage.setVarianceFlag(0);
        usage.setReviewStatus(REVIEW_NONE);
        usage.setNote(req.getNote());
        usageMapper.insert(usage);

        int varianceCount = 0;
        for (UsageLineRequest lineReq : req.getLines()) {
            MaterialBatch batch = requireBatchByNo(lineReq.getBatchNo());
            Material material = materialMapper.selectById(batch.getMaterialId());
            if (material == null || MATERIAL_DISABLED.equals(material.getStatus())) {
                throw new IllegalArgumentException("材料已停用，不能领用: " + lineReq.getBatchNo());
            }
            if (!isUsable(batch, today)) {
                String reason = BATCH_DISPOSED.equals(batch.getStatus()) ? "批号已报废"
                        : BATCH_EXHAUSTED.equals(batch.getStatus()) ? "批号库存已用尽"
                        : "材料已过有效期（" + batch.getExpireDate() + "），过期材料不能领用";
                throw new IllegalArgumentException("批号不能领用【" + batch.getBatchNo() + "】：" + reason);
            }
            BigDecimal plan = lineReq.getPlanQty();
            BigDecimal actual = lineReq.getActualQty();
            if (plan == null || plan.signum() <= 0) {
                throw new IllegalArgumentException("计划用量必须大于 0: " + batch.getBatchNo());
            }
            if (actual == null || actual.signum() <= 0) {
                throw new IllegalArgumentException("实际用量必须大于 0: " + batch.getBatchNo());
            }
            // 条件 UPDATE 原子扣减：并发下库存不足直接 0 行，整单回滚
            if (batchMapper.deductStock(batch.getId(), actual) == 0) {
                throw new IllegalArgumentException("批号库存不足: " + batch.getBatchNo());
            }

            BigDecimal variancePct = actual.subtract(plan).abs()
                    .multiply(BigDecimal.valueOf(100))
                    .divide(plan, VARIANCE_SCALE, RoundingMode.HALF_UP);
            boolean overThreshold = variancePct.compareTo(threshold) > 0;
            if (overThreshold) {
                varianceCount++;
            }

            MaterialUsageLine line = new MaterialUsageLine();
            line.setUsageId(usage.getId());
            line.setBatchId(batch.getId());
            line.setMaterialId(material.getId());
            // 快照固化：材料/批号信息之后变更不影响历史工单
            line.setMaterialName(material.getName());
            line.setBatchNo(batch.getBatchNo());
            line.setCategory(material.getCategory());
            line.setUnit(material.getUnit());
            line.setPlanQty(plan);
            line.setActualQty(actual);
            line.setApplyPart(lineReq.getApplyPart());
            line.setOperator(lineReq.getOperator());
            line.setVariancePct(variancePct);
            line.setReviewStatus(overThreshold ? REVIEW_PENDING : REVIEW_NONE);
            lineMapper.insert(line);
        }

        if (varianceCount > 0) {
            usage.setVarianceFlag(1);
            usage.setReviewStatus(REVIEW_PENDING);
            usageMapper.updateById(usage);
        }

        ledgerService.append(project.getRelicId(), project.getId(), null,
                ACTION_MATERIAL_ISSUE, usage.getUsageNo(),
                "工单领用材料 " + req.getLines().size() + " 项，批号："
                        + joinBatchNos(req.getLines())
                        + (varianceCount > 0 ? "；其中 " + varianceCount + " 项用量差异超阈值，待复核" : ""),
                now);
        return usage;
    }

    /** 领用单详情：单头 + 明细行（含每行累计退料量）+ 退料记录 */
    public Map<String, Object> getUsage(Long usageId) {
        MaterialUsage usage = usageMapper.selectById(usageId);
        if (usage == null) {
            throw new IllegalArgumentException("领用单不存在: id=" + usageId);
        }
        List<MaterialUsageLine> lines = lineMapper.selectList(new LambdaQueryWrapper<MaterialUsageLine>()
                .eq(MaterialUsageLine::getUsageId, usageId)
                .orderByAsc(MaterialUsageLine::getId));
        List<MaterialReturn> returns = returnMapper.selectList(new LambdaQueryWrapper<MaterialReturn>()
                .eq(MaterialReturn::getUsageId, usageId)
                .orderByAsc(MaterialReturn::getId));

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("usage", usage);
        detail.put("lines", lines);
        detail.put("returns", returns);
        return detail;
    }

    public List<MaterialUsage> listUsagesByProject(Long projectId) {
        return usageMapper.selectList(new LambdaQueryWrapper<MaterialUsage>()
                .eq(MaterialUsage::getProjectId, projectId)
                .orderByAsc(MaterialUsage::getId));
    }

    public List<MaterialUsageLine> listLines(Long usageId) {
        return lineMapper.selectList(new LambdaQueryWrapper<MaterialUsageLine>()
                .eq(MaterialUsageLine::getUsageId, usageId)
                .orderByAsc(MaterialUsageLine::getId));
    }

    public List<MaterialReturn> listReturnsByUsage(Long usageId) {
        return returnMapper.selectList(new LambdaQueryWrapper<MaterialReturn>()
                .eq(MaterialReturn::getUsageId, usageId)
                .orderByAsc(MaterialReturn::getId));
    }

    // ------------------------------------------------------------------
    // 退料（关联原领用单）
    // ------------------------------------------------------------------

    /**
     * 退料：必须指定原领用单与原明细行，库存加回原批号；
     * 可分次退，累计不得超过该行实际用量。退料只追加，不改写原领用/差异事实。
     * 所有行均退齐时整单置 RETURNED。
     */
    @Transactional
    public MaterialReturn returnMaterial(MaterialReturnRequest req) {
        MaterialUsage usage = usageMapper.selectById(req.getUsageId());
        if (usage == null) {
            throw new IllegalArgumentException("原领用单不存在: id=" + req.getUsageId());
        }
        MaterialUsageLine line = lineMapper.selectById(req.getLineId());
        if (line == null || !line.getUsageId().equals(usage.getId())) {
            throw new IllegalArgumentException("退料明细行不属于该原领用单");
        }
        BigDecimal qty = req.getReturnQty();
        if (qty == null || qty.signum() <= 0) {
            throw new IllegalArgumentException("退料数量必须大于 0");
        }
        BigDecimal returned = sumReturned(line.getId());
        if (returned.add(qty).compareTo(line.getActualQty()) > 0) {
            throw new IllegalArgumentException("累计退料不能超过该行实际用量 "
                    + line.getActualQty() + line.getUnit()
                    + "（已退 " + returned + line.getUnit() + "）");
        }

        LocalDateTime now = LocalDateTime.now();
        MaterialReturn ret = new MaterialReturn();
        ret.setReturnNo(req.getReturnNo());
        ret.setUsageId(usage.getId());
        ret.setLineId(line.getId());
        ret.setBatchId(line.getBatchId());
        ret.setProjectId(usage.getProjectId());
        ret.setRelicId(usage.getRelicId());
        ret.setReturnQty(qty);
        ret.setReason(req.getReason());
        ret.setReturnedBy(req.getReturnedBy());
        ret.setReturnedAt(now);
        returnMapper.insert(ret);

        batchMapper.addStock(line.getBatchId(), qty);

        // 整单是否全部退齐：每张明细行的累计退料都达到实际用量
        boolean allReturned = true;
        for (MaterialUsageLine sibling : lineMapper.selectList(new LambdaQueryWrapper<MaterialUsageLine>()
                .eq(MaterialUsageLine::getUsageId, usage.getId()))) {
            if (sumReturned(sibling.getId()).compareTo(sibling.getActualQty()) < 0) {
                allReturned = false;
                break;
            }
        }
        if (allReturned) {
            usage.setStatus(USAGE_RETURNED);
            usageMapper.updateById(usage);
        }

        ledgerService.append(usage.getRelicId(), usage.getProjectId(), null,
                ACTION_MATERIAL_RETURN, ret.getReturnNo(),
                "退料关联原领用单 " + usage.getUsageNo() + "，批号 " + line.getBatchNo()
                        + "，退回 " + qty + line.getUnit()
                        + "，部位 " + line.getApplyPart()
                        + "（原操作者：" + line.getOperator() + "）", now);
        return ret;
    }

    // ------------------------------------------------------------------
    // 差异复核
    // ------------------------------------------------------------------

    /** 待复核明细行（定时扫描与排查页共用） */
    public List<MaterialUsageLine> listPendingReviewLines() {
        return lineMapper.selectList(new LambdaQueryWrapper<MaterialUsageLine>()
                .eq(MaterialUsageLine::getReviewStatus, REVIEW_PENDING)
                .orderByAsc(MaterialUsageLine::getId));
    }

    public List<MaterialReview> listReviews(String status) {
        LambdaQueryWrapper<MaterialReview> wrapper = new LambdaQueryWrapper<MaterialReview>()
                .orderByDesc(MaterialReview::getId);
        if (status != null && !status.isEmpty()) {
            wrapper.eq(MaterialReview::getStatus, status);
        }
        return reviewMapper.selectList(wrapper);
    }

    /**
     * 确认复核：OPEN -> CONFIRMED，明细行同步解除待复核；
     * 领用单下再无待复核行时整单复核闭环。操作者始终取自原领用行，不被复核人覆盖。
     */
    @Transactional
    public MaterialReview confirmReview(Long reviewId, String confirmedBy, String note) {
        MaterialReview review = reviewMapper.selectById(reviewId);
        if (review == null) {
            throw new IllegalArgumentException("复核记录不存在: id=" + reviewId);
        }
        if (!REVIEW_OPEN.equals(review.getStatus())) {
            throw new IllegalArgumentException("复核记录已确认，不能重复确认");
        }
        LocalDateTime now = LocalDateTime.now();
        review.setStatus(REVIEW_CONFIRMED);
        review.setConfirmedBy(confirmedBy);
        review.setConfirmedAt(now);
        review.setConfirmNote(note);
        reviewMapper.updateById(review);

        MaterialUsageLine line = lineMapper.selectById(review.getLineId());
        if (line != null && REVIEW_PENDING.equals(line.getReviewStatus())) {
            line.setReviewStatus(REVIEW_CONFIRMED);
            lineMapper.updateById(line);
        }

        Long pending = lineMapper.selectCount(new LambdaQueryWrapper<MaterialUsageLine>()
                .eq(MaterialUsageLine::getUsageId, review.getUsageId())
                .eq(MaterialUsageLine::getReviewStatus, REVIEW_PENDING));
        if (pending == null || pending == 0) {
            MaterialUsage usage = usageMapper.selectById(review.getUsageId());
            if (usage != null && REVIEW_PENDING.equals(usage.getReviewStatus())) {
                usage.setReviewStatus(REVIEW_CONFIRMED);
                usageMapper.updateById(usage);
            }
        }

        ledgerService.append(review.getRelicId(), review.getProjectId(), null,
                ACTION_MATERIAL_REVIEW_CONFIRM, String.valueOf(review.getId()),
                "用量差异复核确认，原操作者：" + review.getOperator()
                        + "，部位：" + review.getApplyPart()
                        + "，偏差 " + review.getVariancePct() + "%，复核人：" + confirmedBy, now);
        return review;
    }

    // ------------------------------------------------------------------
    // 库存对账：按流水重算批号应有库存，与现库存核对
    // ------------------------------------------------------------------

    /**
     * 对账：应有库存 = 累计入库 - 累计领用（实际用量）+ 累计退料。
     * consistent=false 说明库存与修复进度发生偏离，需要排查。
     */
    public Map<String, Object> reconcileBatch(Long batchId) {
        MaterialBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new IllegalArgumentException("材料批号不存在: id=" + batchId);
        }
        BigDecimal inboundTotal = nz(batch.getInboundQty());
        BigDecimal issuedTotal = BigDecimal.ZERO;
        for (MaterialUsageLine line : lineMapper.selectList(new LambdaQueryWrapper<MaterialUsageLine>()
                .eq(MaterialUsageLine::getBatchId, batchId))) {
            issuedTotal = issuedTotal.add(nz(line.getActualQty()));
        }
        BigDecimal returnedTotal = BigDecimal.ZERO;
        for (MaterialReturn ret : returnMapper.selectList(new LambdaQueryWrapper<MaterialReturn>()
                .eq(MaterialReturn::getBatchId, batchId))) {
            returnedTotal = returnedTotal.add(nz(ret.getReturnQty()));
        }
        BigDecimal expected = inboundTotal.subtract(issuedTotal).add(returnedTotal);
        BigDecimal actual = nz(batch.getStockQty());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batchId", batch.getId());
        result.put("batchNo", batch.getBatchNo());
        result.put("stockQty", actual);
        result.put("inboundTotal", inboundTotal);
        result.put("issuedTotal", issuedTotal);
        result.put("returnedTotal", returnedTotal);
        result.put("expectedStockQty", expected);
        result.put("consistent", expected.compareTo(actual) == 0);
        return result;
    }

    // ------------------------------------------------------------------

    private BigDecimal sumReturned(Long lineId) {
        return returnMapper.selectList(new LambdaQueryWrapper<MaterialReturn>()
                        .eq(MaterialReturn::getLineId, lineId)).stream()
                .map(r -> nz(r.getReturnQty()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private String joinBatchNos(List<UsageLineRequest> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append("、");
            }
            sb.append(lines.get(i).getBatchNo());
        }
        return sb.toString();
    }
}
