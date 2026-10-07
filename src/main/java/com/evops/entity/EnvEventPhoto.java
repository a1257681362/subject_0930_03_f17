package com.evops.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 事件现场照片：一行一张，只追加。
 * 复用影像归档物理文件（t_photo_file 内容寻址）；事件关闭前必须至少存在一张。
 */
@Data
@TableName("t_env_event_photo")
public class EnvEventPhoto {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long eventId;

    /** 引用 t_photo_file 物理文件 */
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
