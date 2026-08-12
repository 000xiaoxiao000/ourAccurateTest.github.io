package com.oAT.web.config;

import com.oAT.web.control.LoginInterceptor;
import com.oAT.web.service.ResourceService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final LoginInterceptor loginInterceptor;
    private final ResourceService resourceService;
    private final FrontendProperties frontendProperties;

    public WebConfig(LoginInterceptor loginInterceptor,
                     ResourceService resourceService,
                     FrontendProperties frontendProperties) {
        this.loginInterceptor = loginInterceptor;
        this.resourceService = resourceService;
        this.frontendProperties = frontendProperties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/", "/r/**", "/error", "/share/**", "/share/api/**",
                        "/webhook/**",
                        "/api/auth/login", "/api/auth/register", "/api/auth/me");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(frontendProperties.getAllowedOrigins())
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
        registry.addMapping("/share/api/**")
                .allowedOrigins(frontendProperties.getAllowedOrigins())
                .allowedMethods("GET", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        File resourceRoot = new File(resourceService.getCacheRoot());
        if (!resourceRoot.exists()) {
            resourceRoot.mkdirs();
        }
        registry.addResourceHandler("/r/**")
                .addResourceLocations(resourceRoot.toURI().toString());
    }
}
