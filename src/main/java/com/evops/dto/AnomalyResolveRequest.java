package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.time.LocalDateTime;

/**
 * 押运异常解除：解除后后续节点方可继续登记。
 */
@Data
public class AnomalyResolveRequest {

    @NotBlank(message = "解除处理人不能为空")
    private String resolvedBy;

    @NotBlank(message = "解除说明不能为空")
    private String remark;

    private LocalDateTime resolvedAt;
}
