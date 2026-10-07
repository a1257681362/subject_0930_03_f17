package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 处置任务关闭：仅馆内人员（INTERNAL）可关闭；
 * 外包人员只能提交现场数据，不能关闭任务。
 */
@Data
public class PestTaskCloseRequest {

    @NotBlank(message = "关闭人不能为空")
    private String closedBy;

    /** 关闭人角色：INTERNAL 馆内人员 / OUTSOURCE 外包人员（外包一律拒绝） */
    @NotBlank(message = "关闭人角色不能为空")
    private String closerRole;

    private String closeNote;
}
