package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 发布年度报表指标口径版本：另出新版本，旧版本置 SUPERSEDED 只追加。
 * spec 为空时按默认口径发布。
 */
@Data
public class AnnualCaliberPublishRequest {

    /** 版本名称（如「2025 年度口径」） */
    @NotBlank(message = "口径版本名称不能为空")
    private String name;

    /** 本次口径（为空则沿用默认口径，仍产生新版本） */
    private AnnualCaliberSpec spec;

    /** 相对上一版本的修订原因（首版可不填） */
    private String changeReason;

    @NotBlank(message = "发布人不能为空")
    private String publishedBy;
}
