package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 密集架建档（互斥设备）。
 */
@Data
public class StorageRackCreateRequest {

    @NotBlank(message = "密集架编号不能为空")
    private String rackNo;

    @NotNull(message = "所属库区不能为空")
    private Long areaId;

    /** 互斥设备编号，全库唯一 */
    @NotBlank(message = "互斥设备编号不能为空")
    private String equipmentNo;

    private String name;

    private String aisle;
}
