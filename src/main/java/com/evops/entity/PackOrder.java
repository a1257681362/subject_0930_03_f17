package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 装箱单：一次装箱一行，关联已签押运（借展协议），箱号全库唯一。
 *
 * <p>装箱时把生效规格版本与命中条目<b>全量快照</b>进本表（spec_* 列）：
 * 之后规格发布新版本不影响本单——规格变更不影响已签押运单。</p>
 *
 * <p>发运前证据（spec 快照、actual_*、sealed_*）插入后永不改写；
 * 拆箱只回写 unpack_* 列，不能覆盖发运前证据。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_pack_order")
public class PackOrder extends BaseEntity {

    private String orderNo;
    /** 请求流水号（补传/重试幂等锚点，全库唯一） */
    private String requestNo;

    private Long relicId;
    /** 已签押运单 = 借展协议 id */
    private Long agreementId;
    private String boxNo;

    /** STANDARD 按规格装箱 / SUBSTITUTE 临时替代包装 */
    private String packType;

    private String climateZone;
    /** 本次运输防震要求（缓冲等级下限） */
    private String requiredCushionLevel;

    /** 装箱实测重量（克） */
    private BigDecimal actualWeightG;
    /** 装箱实测外形（毫米） */
    private BigDecimal actualLengthMm;
    private BigDecimal actualWidthMm;
    private BigDecimal actualDepthMm;

    // ---- 规格版本/条目快照（装箱时固化，不可变） ----
    private Long specVersionId;
    private String specVersionNo;
    private Long specItemId;
    private String specMaterial;
    private String specSizeCode;
    private String specCushionLevel;
    private String specClimateZone;
    private BigDecimal specMaxWeightG;
    private BigDecimal specShockLimitG;
    private BigDecimal specFitLengthMinMm;
    private BigDecimal specFitLengthMaxMm;
    private BigDecimal specFitWidthMinMm;
    private BigDecimal specFitWidthMaxMm;
    private BigDecimal specFitDepthMinMm;
    private BigDecimal specFitDepthMaxMm;

    private String packedBy;
    private LocalDateTime sealedAt;

    /** PENDING_APPROVAL / NOTIFY_PENDING / PACKED / UNPACKED / REJECTED */
    private String status;

    // ---- 拆箱回写（只写这几列，不触碰发运前列） ----
    private String unpackedBy;
    private LocalDateTime unpackedAt;
    /** INTACT 完好 / DAMAGED 受损 */
    private String unpackCondition;
    private String unpackNote;
}
