package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 鉴定附件登记项：随意见 / 更正 / 仲裁提交，只追加不改写。
 */
@Data
public class AppraisalAttachmentItem {

    @NotBlank(message = "附件文件名不能为空")
    private String fileName;

    @NotBlank(message = "附件存储路径不能为空")
    private String filePath;

    /** 内容哈希（如 SHA-256），可选 */
    private String fileHash;
}
