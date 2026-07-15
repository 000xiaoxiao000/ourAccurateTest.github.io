package com.oAT.web.control;

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

}
