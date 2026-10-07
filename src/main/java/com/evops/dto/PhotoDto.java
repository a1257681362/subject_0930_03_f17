package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class PhotoDto {

    @NotBlank(message = "照片地址不能为空")
    private String photoUrl;

    private String photoName;

    /** 正面/背面/俯视/细节等 */
    private String viewType;

    private Integer sortNo;
}
