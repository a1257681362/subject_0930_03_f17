package com.evops.common;

/**
 * 修复链路输入缺项 / 参数非法（可由调用方补齐修正）。
 *
 * <p>与状态冲突（并发落败、状态不允许）不同：输入问题不写入 REJECTED 尝试、
 * 不消耗请求流水号——调用方补齐关键字段后，可用同一流水号只重试当前步骤。
 * 错误信息必须指明缺项字段，便于一次性补齐。</p>
 */
public class RepairInputException extends IllegalArgumentException {

    public RepairInputException(String message) {
        super(message);
    }
}
