package com.evops.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 补充记录：已关闭任务唯一允许的新增；只追加，原扫描/差异结果永不重写。
 */
@Data
@TableName("t_inv_supplement")
public class InvSupplement {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    /** 补充说明 */
    private String content;

    /** 记录人 */
    private String operatorName;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
