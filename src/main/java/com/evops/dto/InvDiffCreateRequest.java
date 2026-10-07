package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/** 开立差异单：差异必须能解释（说明+见证人），现场照片随后上传 */
@Data
public class InvDiffCreateRequest {

    @NotBlank(message = "差异说明不能为空（差异必须能解释）")
    private String explanation;

    @NotBlank(message = "见证人不能为空")
    private String witness1;

    /** 第二见证人（可选，与第一见证人不得同名） */
    private String witness2;

    /** 开立人 */
    private String createdBy;
}
