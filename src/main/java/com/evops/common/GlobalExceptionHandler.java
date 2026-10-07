package com.evops.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.evops.gateway.MuseumRepairUnavailableException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务规则冲突：状态非法、编号重复等 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<Void> handleIllegalArgument(IllegalArgumentException ex) {
        return ApiResponse.fail(ex.getMessage());
    }

    /** 文物修复权限已收紧：推进型动作（影像上传）停止 */
    @ExceptionHandler(PermissionClosedException.class)
    public ApiResponse<Void> handlePermissionClosed(PermissionClosedException ex) {
        return ApiResponse.fail(ex.getMessage());
    }

    /** 博物馆文物修复外部依赖无响应：明确告知未送达，绝不包装成成功 */
    @ExceptionHandler(MuseumRepairUnavailableException.class)
    public ApiResponse<Void> handleRepairUnavailable(MuseumRepairUnavailableException ex) {
        return ApiResponse.fail(ex.getMessage());
    }

    /** 请求体校验失败 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<Void> handleValidation(MethodArgumentNotValidException ex) {
        return ApiResponse.fail(firstFieldError(ex.getBindingResult().getFieldErrors()));
    }

    @ExceptionHandler(BindException.class)
    public ApiResponse<Void> handleBind(BindException ex) {
        return ApiResponse.fail(firstFieldError(ex.getBindingResult().getFieldErrors()));
    }

    /** 数据库唯一约束兜底（编号唯一的最后一道防线） */
    @ExceptionHandler(DuplicateKeyException.class)
    public ApiResponse<Void> handleDuplicateKey(DuplicateKeyException ex) {
        log.warn("唯一约束冲突: {}", ex.getMostSpecificCause().getMessage());
        return ApiResponse.fail("编号冲突：文物编号或项目编号已存在");
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleUnexpected(Exception ex) {
        log.error("未预期异常", ex);
        return ApiResponse.fail("系统处理失败");
    }

    private String firstFieldError(java.util.List<FieldError> errors) {
        if (errors == null || errors.isEmpty()) {
            return "请求参数不合法";
        }
        return errors.stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
    }
}
