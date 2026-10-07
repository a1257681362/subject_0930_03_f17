package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;

/**
 * 文物修复权限策略调整：收紧（open=false，影像上传停止推进）或放开（open=true）。
 */
@Data
public class PolicyUpdateRequest {

    /** true 放开影像上传 / false 收紧 */
    @NotNull(message = "开放状态不能为空")
    private Boolean open;

    /** 操作人，留痕用 */
    private String operator;

    /** 调整原因 */
    private String reason;
}
