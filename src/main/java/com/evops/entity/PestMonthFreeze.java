package com.evops.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 监测月份冻结：一个月一行，只追加不解冻。
 * 冻结后该月监测记录与照片不再接受补录/更改，已冻结事实保留可查。
 */
@Data
@TableName("t_pest_month_freeze")
public class PestMonthFreeze {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 监测月份 yyyy-MM */
    private String monitorMonth;

    private String frozenBy;

    private LocalDateTime frozenAt;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
