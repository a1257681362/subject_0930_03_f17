package com.evops.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 包装规格版本：只追加。
 * status: DRAFT 草稿 / ACTIVE 当前生效 / SUPERSEDED 已被新版本取代。
 * 发布新版本时旧 ACTIVE 置 SUPERSEDED，旧版本与条目内容永不改写。
 */
@Data
@TableName("t_pack_spec_version")
public class PackSpecVersion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String versionNo;
    private String title;
    private String remark;
    private String status;
    private String publishedBy;
    private LocalDateTime publishedAt;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
