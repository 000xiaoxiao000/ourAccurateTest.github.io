package com.oAT.web.control.api;

import com.oAT.web.control.entity.ResultNotified;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.oAT.web.control.api")
public class ApiExceptionHandler {

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
}
