package com.oAT.web.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Component
public class FrontendProperties {

    @Value("${oat.frontend.base-url:http://localhost:5176}")
    private String baseUrl;

    @Value("${oat.frontend.allowed-origins:${oat.frontend.base-url:http://localhost:5176}}")
    private String allowedOrigins;

    public String getBaseUrl() {
        if (!StringUtils.hasText(baseUrl)) {
            return "http://localhost:5176";
        }
        String normalized = baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return requireTrustedHttpOrigin(normalized);
    }

    public String[] getAllowedOrigins() {
        if (!StringUtils.hasText(allowedOrigins)) {
            return new String[]{getBaseUrl()};
        }
        return java.util.Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toArray(String[]::new);
    }

    public String url(String path) {
        String safePath = StringUtils.hasText(path) ? path.trim() : "/projects";
        if (safePath.startsWith("//") || safePath.startsWith("http://") || safePath.startsWith("https://")) {
            safePath = "/projects";
        } else if (!safePath.startsWith("/")) {
            safePath = "/" + safePath;
        }
        return getBaseUrl() + safePath;
    }

    public String loginUrl(String redirect) {
        String loginUrl = url("/login");
        if (StringUtils.hasText(redirect)) {
            return loginUrl + "?redirect=" + URLEncoder.encode(redirect, StandardCharsets.UTF_8);
        }
        return loginUrl;
    }

    private String requireTrustedHttpOrigin(String origin) {
        URI uri = URI.create(origin);
        if (!StringUtils.hasText(uri.getScheme()) || !StringUtils.hasText(uri.getHost())) {
            throw new IllegalArgumentException("oat.frontend.base-url must be an absolute HTTP(S) origin");
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("oat.frontend.base-url only supports HTTP(S) origins");
        }
        if (StringUtils.hasText(uri.getRawPath()) && !"/".equals(uri.getRawPath())) {
            throw new IllegalArgumentException("oat.frontend.base-url must not contain a path");
        }
        if (StringUtils.hasText(uri.getRawQuery()) || StringUtils.hasText(uri.getRawFragment())) {
            throw new IllegalArgumentException("oat.frontend.base-url must not contain query or fragment");
        }
        return uri.toString();
    }
}
