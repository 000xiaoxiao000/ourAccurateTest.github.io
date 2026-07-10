package com.oAT.web.control;

import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;

@Controller
public class CommonControl implements ErrorController {
    private static final String ERROR_PATH = "/error";

    @RequestMapping("/error/404")
    @ResponseBody
    public Map<String, Object> open404View() {
        return errorBody(HttpStatus.NOT_FOUND, "找不到指定资源");
    }

    @RequestMapping(ERROR_PATH)
    @ResponseBody
    public Map<String, Object> error(HttpServletRequest request) {
        Object status = request.getAttribute("javax.servlet.error.status_code");
        HttpStatus httpStatus = status instanceof Integer ? HttpStatus.resolve((Integer) status) : null;
        String message = request.getAttribute("javax.servlet.error.message") instanceof String
                ? (String) request.getAttribute("javax.servlet.error.message")
                : "请求处理失败";
        return errorBody(httpStatus == null ? HttpStatus.INTERNAL_SERVER_ERROR : httpStatus, message);
    }

    private Map<String, Object> errorBody(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("result", false);
        body.put("success", false);
        body.put("message", message);
        body.put("errorMessage", status.name());
        body.put("status", status.value());
        return body;
    }
}
