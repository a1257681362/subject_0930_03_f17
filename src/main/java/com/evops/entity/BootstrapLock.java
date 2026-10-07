package com.evops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 「博物馆文物修复档案起步」幂等锁。
 * 固定 id=1（CHECK 约束保证），全表最多一行：存在即表示已播种。
 */
@Data
@TableName("t_bootstrap_lock")
public class BootstrapLock {

    @TableId(type = IdType.INPUT)
    private Integer id;

    private Long relicId;

    private Long projectId;

    private LocalDateTime bootstrappedAt;
}
