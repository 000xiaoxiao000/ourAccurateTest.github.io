package com.oAT.web.control;

import com.oAT.web.config.FrontendProperties;
import com.aiplatform.client.AiPlatformProperties;
import com.oAT.web.logging.AuditLogger;
import com.oAT.web.logging.LogContext;
import com.oAT.web.logging.LogFields;
import com.oAT.web.service.entity.UserVo;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static com.oAT.web.common.UtilJson.JSON_MAPPER;

@Component
public class LoginInterceptor implements HandlerInterceptor {
    private static final String AI_TOOL_TOKEN_HEADER = "X-OAT-AI-TOOL-TOKEN";
    private static final String GENERIC_AI_TOOL_TOKEN_HEADER = "X-AI-TOOL-TOKEN";

    private final FrontendProperties frontendProperties;
    private final AiPlatformProperties aiPlatformProperties;
    private final AuditLogger auditLogger;

    public LoginInterceptor(FrontendProperties frontendProperties,
                            AiPlatformProperties aiPlatformProperties,
                            AuditLogger auditLogger) {
        this.frontendProperties = frontendProperties;
        this.aiPlatformProperties = aiPlatformProperties;
        this.auditLogger = auditLogger;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, @NotNull HttpServletResponse response, @NotNull Object handler) {
        Boolean share = (Boolean) request.getAttribute("_share");
        if (share != null && share) {
            return true;
        }
        if (isAuthorizedAiToolRequest(request)) {
            return true;
        }
        UserVo user = (UserVo) request.getSession().getAttribute("user");
        if (user == null) {
            try {
                auditLogger.securityFailure("auth.required", LogFields.map(
                        "method", request.getMethod(),
                        "path", request.getRequestURI(),
                        "client_ip", LogContext.clientIp(request),
                        "api_request", isApiRequest(request)));
                if (isApiRequest(request)) {
                    writeUnauthorizedApiResponse(response);
                    return false;
                }
                String redirect = buildRedirectPath(request);
                response.sendRedirect(frontendProperties.loginUrl(redirect));
                return false;
            } catch (IOException e) {
                throw new RuntimeException("登录重定向失败!", e);
            }
        }
        LogContext.putUser(user);
        return true;
    }

    private boolean isAuthorizedAiToolRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null || !uri.startsWith("/api/ai-tools/")) {
            return false;
        }
        String expectedToken = aiPlatformProperties.getToolToken();
        if (!StringUtils.hasText(expectedToken)) {
            return false;
        }
        String actualToken = request.getHeader(GENERIC_AI_TOOL_TOKEN_HEADER);
        if (!StringUtils.hasText(actualToken)) {
            actualToken = request.getHeader(AI_TOOL_TOKEN_HEADER);
        }
        return StringUtils.hasText(actualToken)
                && MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8),
                actualToken.getBytes(StandardCharsets.UTF_8));
    }

    private boolean isApiRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri != null && (uri.startsWith("/api/") || uri.startsWith("/share/api/"))) {
            return true;
        }
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains(MediaType.APPLICATION_JSON_VALUE);
    }

    private String buildRedirectPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String queryString = request.getQueryString();
        String redirect = StringUtils.hasText(uri) ? uri : "/projects";
        if (StringUtils.hasText(queryString)) {
            redirect += "?" + queryString;
        }
        return normalizeRedirect(redirect);
    }

    static String normalizeRedirect(String redirect) {
        if (!StringUtils.hasText(redirect)) {
            return "/projects";
        }

        String normalized = redirect.trim();
        for (int i = 0; i < 8; i++) {
            String decoded = URLDecoder.decode(normalized, StandardCharsets.UTF_8);
            if (decoded.equals(normalized)) {
                break;
            }
            normalized = decoded;
        }

        if (normalized.startsWith("http://") || normalized.startsWith("https://") || normalized.startsWith("//")) {
            return "/projects";
        }
        if (normalized.startsWith("/\\") || normalized.indexOf('\\') >= 0 || containsControlCharacter(normalized)) {
            return "/projects";
        }
        if (!normalized.startsWith("/")) {
            return "/projects";
        }
        if (normalized.startsWith("/login") || normalized.startsWith("/index.html")) {
            String nested = extractRedirectParameter(normalized);
            return StringUtils.hasText(nested) ? normalizeRedirect(nested) : "/projects";
        }
        if (normalized.contains("redirect=/index.html") || normalized.contains("redirect=%2Findex.html")) {
            return "/projects";
        }
        return normalized;
    }

    private static boolean containsControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) <= 0x1F || value.charAt(i) == 0x7F) {
                return true;
            }
        }
        return false;
    }

    private static String extractRedirectParameter(String value) {
        int redirectIndex = value.indexOf("redirect=");
        if (redirectIndex < 0) {
            return null;
        }
        String nested = value.substring(redirectIndex + "redirect=".length());
        int ampIndex = nested.indexOf('&');
        if (ampIndex >= 0) {
            nested = nested.substring(0, ampIndex);
        }
        return nested;
    }

    private void writeUnauthorizedApiResponse(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(JSON_MAPPER.writeValueAsString(new ApiUnauthorizedBody()));
    }

    private static final class ApiUnauthorizedBody {
        public final boolean result = false;
        public final boolean success = false;
        public final String message = "未登录或登录已过期";
        public final String errorMessage = "AUTH_REQUIRED";
    }

}
