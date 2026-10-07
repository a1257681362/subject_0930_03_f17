package com.evops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 权限策略（单行 id=1）：文物修复相关操作的收紧开关。
 *
 * <p>{@code repairPhotoUploadOpen=false} 表示博物馆文物修复权限被收紧，
 * 影像上传立即停止推进；已确认的影像版本、物理文件与台账事实不受影响、全部保留。</p>
 *
 * <p>{@code valuationChangeOpen=false} 表示「文物保险估值变更留痕」入口被收紧：
 * 估值登记/金额修订提交/双人审批签署/批单/签约快照等一切推进型动作在服务入口
 * 直接拒绝，换任何调用地址都不放行；已生效估值版本、批单与借展快照原样保留，
 * 只读查询（含财务金额视图）不受影响。</p>
 */
@Data
@TableName("t_permission_policy")
public class PermissionPolicy {

    @TableId(type = IdType.INPUT)
    private Integer id;

    /** 影像上传是否开放：true 开放 / false 已收紧 */
    private Boolean repairPhotoUploadOpen;

    /** 文物保险估值变更留痕入口是否开放：true 开放 / false 已收紧 */
    private Boolean valuationChangeOpen;

    private String updatedBy;

    private LocalDateTime updatedAt;
}
