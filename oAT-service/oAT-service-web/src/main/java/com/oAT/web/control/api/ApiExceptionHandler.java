package com.oAT.web.control.api;

import com.oAT.web.logging.AuditLogger;
import com.oAT.web.logging.LogFields;
import com.oAT.web.control.entity.ResultNotified;
import com.oAT.web.exceptions.BusinessException;
import com.oAT.web.exceptions.FriendlyException;
import com.oAT.web.exceptions.UserOperationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

@RestControllerAdvice(basePackages = "com.oAT.web.control.api")
public class ApiExceptionHandler {
    private static final Logger logger = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final AuditLogger auditLogger;

    public ApiExceptionHandler(AuditLogger auditLogger) {
        this.auditLogger = auditLogger;
    }

    @ExceptionHandler(ServletRequestBindingException.class)
    public ResponseEntity<ResultNotified<Object>> handleSessionRequired(ServletRequestBindingException e) {
        auditLogger.securityFailure("session.required", LogFields.map("reason", safeMessage(e.getMessage(), "missing session")));
        ResultNotified<Object> result = new ResultNotified<>(false, "未登录或登录已过期");
        result.setErrorMessage("AUTH_REQUIRED");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(result);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ResultNotified<Object>> handleBadRequest(IllegalArgumentException e) {
        logger.debug("event=api.bad_request {}", LogFields.of(LogFields.map("reason", safeMessage(e.getMessage(), "invalid request"))));
        ResultNotified<Object> result = new ResultNotified<>(false, e.getMessage() == null ? "请求参数不合法" : e.getMessage());
        result.setErrorMessage("BAD_REQUEST");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(result);
    }

    @ExceptionHandler(UserOperationException.class)
    public ResponseEntity<ResultNotified<Object>> handleUserOperation(UserOperationException e) {
        logger.info("event=api.user_operation_failed {}", LogFields.of(LogFields.map("reason", safeMessage(e.getMessage(), "user operation failed"))));
        ResultNotified<Object> result = new ResultNotified<>(false, safeMessage(e.getMessage(), "操作失败，请检查输入后重试"));
        result.setErrorMessage("USER_OPERATION_FAILED");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(result);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ResultNotified<Object>> handleBusiness(BusinessException e) {
        logger.warn("event=api.business_error {}", LogFields.of(LogFields.map("reason", safeMessage(e.getMessage(), "business error"))));
        ResultNotified<Object> result = new ResultNotified<>(false, safeMessage(e.getMessage(), "业务处理失败，请稍后重试"));
        result.setErrorMessage("BUSINESS_ERROR");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(result);
    }

    @ExceptionHandler(FriendlyException.class)
    public ResponseEntity<ResultNotified<Object>> handleFriendly(FriendlyException e) {
        logger.info("event=api.friendly_error {}", LogFields.of(LogFields.map("reason", safeMessage(e.getMessage(), "friendly error"))));
        ResultNotified<Object> result = new ResultNotified<>(false, safeMessage(e.getMessage(), "请求处理失败，请稍后重试"));
        result.setErrorMessage("FRIENDLY_ERROR");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(result);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ResultNotified<Object>> handleUnexpected(Exception e) {
        if (isClientDisconnected(e)) {
            logger.debug("客户端已断开连接，停止写入 API 响应: {}", e.getMessage());
            return null;
        }
        logger.error("API request failed unexpectedly", e);
        ResultNotified<Object> result = new ResultNotified<>(false, "系统暂时无法处理请求，请稍后重试");
        result.setErrorMessage("INTERNAL_ERROR");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
    }

    private boolean isClientDisconnected(Throwable error) {
        Throwable current = error;
        int inspected = 0;
        while (current != null && inspected++ < 12) {
            if (current instanceof AsyncRequestNotUsableException) return true;
            String type = current.getClass().getName();
            String message = current.getMessage();
            if (type.endsWith("ClientAbortException") || containsIgnoreCase(message, "broken pipe")
                    || containsIgnoreCase(message, "connection reset by peer")
                    || containsIgnoreCase(message, "响应不可用")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean containsIgnoreCase(String value, String expected) {
        return value != null && expected != null && value.toLowerCase().contains(expected.toLowerCase());
    }

    private String safeMessage(String message, String fallback) {
        return message == null || message.isBlank() ? fallback : message;
    }
}
