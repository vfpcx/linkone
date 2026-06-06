package com.cangchu.common.exception;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import com.cangchu.common.response.R;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 全局异常处理器
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<R<Void>> handleBizException(BizException e, HttpServletRequest request) {
        String traceId = UUID.randomUUID().toString().substring(0, 13);
        log.warn("[{}] BizException: code={}, message={}", traceId, e.getCode(), e.getMessage());
        return ResponseEntity.ok(R.error(e.getCode(), e.getMessage(), e.getDetails(), traceId));
    }

    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<R<Void>> handleNotLoginException(NotLoginException e) {
        String traceId = UUID.randomUUID().toString().substring(0, 13);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(R.error(ErrorCode.AUTH_BASIC_001.getCode(), "您尚未登录，请先登录", null, traceId));
    }

    @ExceptionHandler(NotRoleException.class)
    public ResponseEntity<R<Void>> handleNotRoleException(NotRoleException e) {
        String traceId = UUID.randomUUID().toString().substring(0, 13);
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(R.error(ErrorCode.PERMISSION_ROLE_001.getCode(), "您没有此操作的权限", null, traceId));
    }

    @ExceptionHandler(NotPermissionException.class)
    public ResponseEntity<R<Void>> handleNotPermissionException(NotPermissionException e) {
        String traceId = UUID.randomUUID().toString().substring(0, 13);
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(R.error(ErrorCode.PERMISSION_ROLE_001.getCode(), "您没有此操作的权限", null, traceId));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Void>> handleValidationException(MethodArgumentNotValidException e) {
        String traceId = UUID.randomUUID().toString().substring(0, 13);
        List<Map<String, String>> fields = new ArrayList<>();
        for (FieldError fieldError : e.getBindingResult().getFieldErrors()) {
            fields.add(Map.of("field", fieldError.getField(), "message", fieldError.getDefaultMessage()));
        }
        return ResponseEntity.ok(R.error(ErrorCode.VALIDATION_BASIC_001.getCode(),
                "参数校验失败", Map.of("fields", fields), traceId));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleException(Exception e, HttpServletRequest request) {
        String traceId = UUID.randomUUID().toString().substring(0, 13);
        log.error("[{}] Unknown exception", traceId, e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(R.error(ErrorCode.SYSTEM_INTERNAL_001.getCode(), "系统繁忙，请稍后再试", null, traceId));
    }
}
