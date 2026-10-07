package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 入藏照片：入藏登记关联的照片，一张照片一行。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_relic_photo")
public class RelicPhoto extends BaseEntity {

    private Long intakeId;

    private Long relicId;

    /** 照片地址/相对路径 */
    private String photoUrl;

    private String photoName;

    /** 视角：正面/背面/俯视/细节等 */
    private String viewType;

    private Integer sortNo;
}
