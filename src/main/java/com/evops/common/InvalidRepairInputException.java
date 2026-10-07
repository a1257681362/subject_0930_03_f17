package com.evops.common;

/**
 * 修复链路请求输入缺项/不合法：错误信息必须指出缺项，
 * 且不属于并发冲突——不落 REJECTED 尝试审计、不占用请求流水号，
 * 调用方补齐缺项后用同一流水号只重试当前步骤即可。
 */
public class InvalidRepairInputException extends IllegalArgumentException {

    public InvalidRepairInputException(String message) {
        super(message);
    }
}
