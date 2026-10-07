package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * 归还验收差异补录：验收闭环后复查发现的差异只追加登记。
 * result 只能为 DAMAGED/MISSING；补录不重新打开已完成归还。
 */
@Data
public class ReturnCheckSuppRequest {

    @NotNull(message = "原清单行 id 不能为空")
    private Long manifestLineId;

    @NotBlank(message = "补录差异类型不能为空")
    private String result;

    @NotBlank(message = "补录差异必须填写差异说明")
    private String diffNote;

    @NotBlank(message = "补录人不能为空")
    private String supplementedBy;

    private LocalDateTime supplementedAt;
}
