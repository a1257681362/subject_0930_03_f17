package com.evops.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 差异单现场照片：内容寻址（同文件全库只一份），复核前至少一张。
 */
@Data
@TableName("t_inv_diff_photo")
public class InvDiffPhoto {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long diffId;

    private Long fileId;

    private String contentHash;

    private String photoName;

    private String uploadedBy;

    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
