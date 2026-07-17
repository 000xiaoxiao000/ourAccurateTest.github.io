package com.oAT.web.control.api;

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

@RestControllerAdvice(basePackages = "com.oAT.web.control.api")
public class ApiExceptionHandler {
    private static final Logger logger = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ServletRequestBindingException.class)
    public ResponseEntity<ResultNotified<Object>> handleSessionRequired(ServletRequestBindingException e) {
        ResultNotified<Object> result = new ResultNotified<>(false, "未登录或登录已过期");
        result.setErrorMessage("AUTH_REQUIRED");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(result);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ResultNotified<Object>> handleBadRequest(IllegalArgumentException e) {
        ResultNotified<Object> result = new ResultNotified<>(false, e.getMessage() == null ? "请求参数不合法" : e.getMessage());
        result.setErrorMessage("BAD_REQUEST");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(result);
    }

    @ExceptionHandler(UserOperationException.class)
    public ResponseEntity<ResultNotified<Object>> handleUserOperation(UserOperationException e) {
        ResultNotified<Object> result = new ResultNotified<>(false, safeMessage(e.getMessage(), "操作失败，请检查输入后重试"));
        result.setErrorMessage("USER_OPERATION_FAILED");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(result);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ResultNotified<Object>> handleBusiness(BusinessException e) {
        ResultNotified<Object> result = new ResultNotified<>(false, safeMessage(e.getMessage(), "业务处理失败，请稍后重试"));
        result.setErrorMessage("BUSINESS_ERROR");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(result);
    }

    @ExceptionHandler(FriendlyException.class)
    public ResponseEntity<ResultNotified<Object>> handleFriendly(FriendlyException e) {
        ResultNotified<Object> result = new ResultNotified<>(false, safeMessage(e.getMessage(), "请求处理失败，请稍后重试"));
        result.setErrorMessage("FRIENDLY_ERROR");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(result);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ResultNotified<Object>> handleUnexpected(Exception e) {
        logger.error("API request failed unexpectedly", e);
        ResultNotified<Object> result = new ResultNotified<>(false, "系统暂时无法处理请求，请稍后重试");
        result.setErrorMessage("INTERNAL_ERROR");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
    }

    private String safeMessage(String message, String fallback) {
        return message == null || message.isBlank() ? fallback : message;
    }
}
