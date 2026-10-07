package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/** 重基线：借还/展陈变更后生成新基线版本并回放旧版本扫描 */
@Data
public class InvRebaselineRequest {

    @NotBlank(message = "操作人不能为空")
    private String operatorName;
}
