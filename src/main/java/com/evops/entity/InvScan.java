package com.evops.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 清点扫描记录：只追加。工作人员扫描箱号与展签，系统按基线判定。
 * matchResult: MATCHED 相符 / MISPLACED 错位 / ABNORMAL 状态异常 / UNEXPECTED 未在基线。
 * 重基线时旧版本扫描逐条回放为新版本扫描（replayedFromId 溯源），旧行不改写。
 */
@Data
@TableName("t_inv_scan")
public class InvScan {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    private Integer baselineVersion;

    /** 扫描箱号 */
    private String boxNo;

    /** 扫描展签 */
    private String displayNo;

    /** 扫描点位（可选） */
    private String pointCode;

    /** 命中的基线行（未在基线为 NULL） */
    private Long matchedLineId;

    /** MATCHED / MISPLACED / ABNORMAL / UNEXPECTED */
    private String matchResult;

    /** 判定说明（错位期望/实际、异常原因等） */
    private String matchDetail;

    /** 回放来源扫描 id（重基线回放产生；人工扫描为 NULL） */
    private Long replayedFromId;

    private String scannedBy;

    private LocalDateTime scannedAt;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
