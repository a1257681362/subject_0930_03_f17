package com.evops.gateway;

/**
 * 博物馆文物修复外部依赖无响应：装箱确认通知未被外部依赖接受。
 * 抛出本异常表示<b>不能视为通知成功</b>，装箱单必须停留在待确认状态。
 */
public class MuseumRepairUnavailableException extends RuntimeException {

    public MuseumRepairUnavailableException(String message) {
        super(message);
    }

    public MuseumRepairUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
