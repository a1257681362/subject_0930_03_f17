package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/** 异常事件处置登记：必须指定处置人；关闭前还须关联至少一张现场照片 */
@Data
public class EventHandleRequest {

    @NotBlank(message = "处置人不能为空")
    private String handler;

    @NotBlank(message = "处置说明不能为空")
    private String handleRemark;
}
