package com.evops.common;

/**
 * 文物修复权限被收紧时抛出：影像上传等推进型动作必须立即停止。
 * 已确认的影像版本与台账事实不受影响、继续保留。
 */
public class PermissionClosedException extends RuntimeException {

    public PermissionClosedException(String message) {
        super(message);
    }
}
