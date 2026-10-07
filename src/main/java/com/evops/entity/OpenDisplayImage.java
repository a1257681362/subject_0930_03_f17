package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 开放展示授权影像：公众页面只展示标记内授权的影像子集。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_open_display_image")
public class OpenDisplayImage extends BaseEntity {

    private Long grantId;

    /** 指向 t_relic_image.id */
    private Long imageId;
}
