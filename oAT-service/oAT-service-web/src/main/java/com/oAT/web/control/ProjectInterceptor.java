package com.oAT.web.control;

import com.oAT.web.common.PaletteColors;
import com.oAT.web.service.AppService;
import com.oAT.web.service.ProjectService;
import com.oAT.web.service.entity.AppVo;
import com.oAT.web.service.entity.ProjectVo;
import com.oAT.web.service.entity.UserVo;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;


@Component
public class ProjectInterceptor implements HandlerInterceptor {

    @Autowired
    ProjectService projectService;
    @Autowired
    AppService appService;

    @Value("${ai.llm.enabled:true}")
    private boolean aiLlmEnabled;

    @Value("${ai.llm.timeout:120}")
    private int aiTimeout;

    @Override
    public boolean preHandle(HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler)
            throws Exception {
        String projectId;
        UserVo user;
        ProjectVo project;
        String requestUri = request.getRequestURI();
        if ("/api/projects".equals(requestUri) || "/api/projects/".equals(requestUri)) {
            return true;
        }
        if (requestUri.startsWith("/api/projects/")) {
            String[] parts = requestUri.split("/");
            if (parts.length <= 3 || !StringUtils.hasText(parts[3])) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "projectId must not be empty");
                return false;
            }
            projectId = parts[3];
        } else {
            String[] parts = requestUri.split("/");
            if (!requestUri.startsWith("/p/") || parts.length <= 2 || !StringUtils.hasText(parts[2])) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "project path must start with /p/{projectId}");
                return false;
            }
            projectId = parts[2];
        }


        // 如果为共享请求，则跳过项目权限验证
        Boolean share = (Boolean) request.getAttribute("_share");
        if (share != null && share) {
            project = projectService.getProject(projectId);
            request.setAttribute("project", project);
            request.setAttribute("apps", appService.getAppList(projectId));
            request.setAttribute("aiLlmEnabled", aiLlmEnabled);
            setMascotPrimary(request, project);
            return true;
        }
        // 验证用户是否拥有项目权限
        user = (UserVo) request.getSession().getAttribute("user");
        Assert.notNull(user, "user must be logging state");
        project = projectService.getProjectByProjectIdAndMemberId(projectId, user.getId());
        if (project == null) {
            request.setAttribute("errorMessage", "找不到指定项目,或者您没有该项目的访问权限");
            request.getRequestDispatcher("/error/404").forward(request, response);
            return false;
        }
        List<AppVo> apps = appService.getAppList(projectId);
        request.setAttribute("apps", apps);
        request.setAttribute("project", project);
        request.setAttribute("aiLlmEnabled", aiLlmEnabled);
        setMascotPrimary(request, project);
        request.setAttribute("aiTimeout", aiTimeout);
        return true;
    }


    @Override
    public void postHandle(HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler,
                           org.springframework.web.servlet.ModelAndView modelAndView) {
    }

    @Override
    public void afterCompletion(HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler,
                                Exception ex) {
    }

    /**
     * 根据 projectId 和 projectName 计算与 AIInteractive 页面一致的 mascotPrimary 颜色，
     * 使所有页面的悬浮小人与 AIInteractive 页面的小人颜色统一。
     */
    private void setMascotPrimary(HttpServletRequest request, ProjectVo project) {
        String mascotPrimary = computeMascotPrimary(project.getId(), project.getName());
        request.setAttribute("mascotPrimary", mascotPrimary);
    }

    private String computeMascotPrimary(String projectId, String projectName) {
        int seed = positiveHash(projectId + ":" + projectName);
        return PaletteColors.pickPrimary(seed / 5 + 13);
    }

    private int positiveHash(String value) {
        int hash = value == null ? 0 : value.hashCode();
        if (hash == Integer.MIN_VALUE) {
            return 0;
        }
        return Math.abs(hash);
    }

}
