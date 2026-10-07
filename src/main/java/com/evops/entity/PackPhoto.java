package com.evops.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 装箱照片：与箱号绑定，按阶段只追加（内容寻址，同文件只一份物理件）。
 * photoStage: BEFORE_SHIP 发运前装箱证据 / AFTER_UNPACK 拆箱后包装状态证据。
 * 拆箱回写另写 AFTER_UNPACK 行，永不覆盖、不删除 BEFORE_SHIP 行。
 */
@Data
@TableName("t_pack_photo")
public class PackPhoto {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;
    /** 箱号冗余快照：照片与箱号绑定 */
    private String boxNo;
    private Long fileId;
    private String contentHash;
    private String photoName;
    private String photoStage;
    private String uploadedBy;
    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
