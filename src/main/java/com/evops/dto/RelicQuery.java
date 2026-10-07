package com.evops.dto;

import lombok.Data;

@Data
public class RelicQuery {

    /** 可选：按状态过滤 ACTIVE/REVOKED；不传查全部 */
    private String status;

    /** 可选：按编号或名称模糊查 */
    private String keyword;
}
