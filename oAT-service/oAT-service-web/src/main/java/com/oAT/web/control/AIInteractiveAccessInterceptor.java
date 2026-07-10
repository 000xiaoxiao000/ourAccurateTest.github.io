package com.oAT.web.control;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class AIInteractiveAccessInterceptor implements HandlerInterceptor {

    @Value("${ai.llm.enabled:true}")
    private boolean aiLlmEnabled;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (aiLlmEnabled) {
            return true;
        }

        request.setAttribute("errorMessage", "AI 功能未启用");
        request.getRequestDispatcher("/error/404").forward(request, response);
        return false;
    }
}
