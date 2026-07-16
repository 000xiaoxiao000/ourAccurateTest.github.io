package com.oAT.web.control.api;

import com.oAT.web.api.common.ApiSummaries.AppSummary;
import com.oAT.web.api.common.ApiSummaries.UserSummary;
import com.oAT.web.api.context.FrontendContextPayloads.DeleteProjectRequest;
import com.oAT.web.api.context.FrontendContextPayloads.LoginRequest;
import com.oAT.web.api.context.FrontendContextPayloads.ProjectContext;
import com.oAT.web.api.context.FrontendContextPayloads.ProjectSummary;
import com.oAT.web.api.context.FrontendContextPayloads.SaveProjectRequest;
import com.oAT.web.control.entity.ResultNotified;
import com.oAT.web.exceptions.UserOperationException;
import com.oAT.web.service.AppService;
import com.oAT.web.service.ProjectService;
import com.oAT.web.service.SystemLogService;
import com.oAT.web.service.UserService;
import com.oAT.web.service.entity.AppVo;
import com.oAT.web.service.entity.ProjectMemberVo;
import com.oAT.web.service.entity.ProjectVo;
import com.oAT.web.service.entity.SystemLogVo;
import com.oAT.web.service.entity.UserRegisterVo;
import com.oAT.web.service.entity.UserVo;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

import java.util.List;

@RestController
public class FrontendAuthProjectApiControl {
    private final UserService userService;
    private final ProjectService projectService;
    private final AppService appService;
    private final SystemLogService systemLogService;

    public FrontendAuthProjectApiControl(UserService userService,
                                         ProjectService projectService,
                                         AppService appService,
                                         SystemLogService systemLogService) {
        this.userService = userService;
        this.projectService = projectService;
        this.appService = appService;
        this.systemLogService = systemLogService;
    }

    @GetMapping("/api/auth/me")
    public ResponseEntity<ResultNotified<UserSummary>> me(@SessionAttribute(required = false) UserVo user) {
        if (user == null) {
            ResultNotified<UserSummary> result = new ResultNotified<>(false, "未登录或登录已过期", null);
            result.setErrorMessage("AUTH_REQUIRED");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(result);
        }
        return ResponseEntity.ok(ok("获取当前用户成功", toUserSummary(user)));
    }

    @PostMapping("/api/auth/login")
    public ResultNotified<UserSummary> login(HttpSession session, @RequestBody LoginRequest request) throws UserOperationException {
        Assert.notNull(request, "请求体不能为空");
        UserVo user = userService.doLogin(request.getNameOrEmail(), request.getNameOrEmail(), request.getPassword());
        session.setAttribute("user", user);
        return ok("登录成功", toUserSummary(user));
    }

    @PostMapping("/api/auth/register")
    public ResultNotified<String> register(@RequestBody UserRegisterVo request) {
        Assert.notNull(request, "请求体不能为空");
        userService.doRegister(request);
        return ok("注册成功", "OK");
    }

    @PostMapping("/api/auth/logout")
    public ResultNotified<String> logout(HttpSession session) {
        session.removeAttribute("user");
        return ok("注销成功", "OK");
    }

    @GetMapping("/api/projects")
    public ResultNotified<List<ProjectSummary>> projects(@SessionAttribute UserVo user) {
        return ok("获取项目列表成功", projectService.findProjectByMemberId(user.getId()).stream().map(this::toProjectSummary).toList());
    }

    @PostMapping("/api/projects")
    public ResultNotified<ProjectSummary> createProject(@SessionAttribute UserVo user,
                                                        @RequestBody SaveProjectRequest request) {
        Assert.notNull(request, "请求体不能为空");
        Assert.hasText(request.getName(), "项目名称不能为空");
        ProjectService.CreateProjectParam param = new ProjectService.CreateProjectParam(request.getName(), request.getDescribe(), user.getId());
        param.userName = user.getName();
        return ok("项目创建成功", toProjectSummary(projectService.createProject(param)));
    }

    @PostMapping("/api/projects/{projectId}")
    public ResultNotified<ProjectSummary> updateProject(@PathVariable String projectId,
                                                        @SessionAttribute UserVo user,
                                                        @RequestBody SaveProjectRequest request) {
        ensureProjectAccess(projectId, user);
        ProjectVo project = projectService.getProject(projectId);
        project.setName(request.getName());
        project.setDescribe(request.getDescribe());
        return ok("项目更新成功", toProjectSummary(projectService.updateProject(project)));
    }

    @PostMapping("/api/projects/{projectId}/delete")
    public ResultNotified<String> deleteProject(@PathVariable String projectId,
                                                @SessionAttribute UserVo user,
                                                @RequestBody DeleteProjectRequest request) throws UserOperationException {
        ensureProjectAccess(projectId, user);
        projectService.deleteProject(projectId, user.getId(), request == null ? null : request.getPassword());
        return ok("项目删除成功", projectId);
    }

    @GetMapping("/api/projects/{projectId}/context")
    public ResultNotified<ProjectContext> projectContext(@PathVariable String projectId,
                                                         @SessionAttribute UserVo user) {
        ProjectVo project = ensureProjectAccess(projectId, user);
        List<AppSummary> apps = appService.getAppList(projectId).stream().map(this::toAppSummary).toList();
        ProjectContext context = new ProjectContext();
        context.setCurrentUser(toUserSummary(user));
        context.setProject(toProjectSummary(project));
        context.setApps(apps);
        context.setRecentLogs(recentLogs(projectId));
        context.setCurrentUserRole(resolveUserRole(projectId, user));
        context.setAppCount(apps.size());
        context.setOnlineAppCount((int) apps.stream().filter(app -> app.getOnlineCount() > 0).count());
        return ok("获取项目上下文成功", context);
    }

    private ProjectVo ensureProjectAccess(String projectId, UserVo user) {
        ProjectVo project = projectService.getProjectByProjectIdAndMemberId(projectId, user.getId());
        Assert.notNull(project, "找不到指定项目,或者您没有该项目的访问权限");
        return project;
    }

    private String resolveUserRole(String projectId, UserVo user) {
        for (ProjectMemberVo member : projectService.getProjectMembers(projectId)) {
            if (user.getId().equals(member.getMemberId()) && member.getRole() != null) {
                return member.getRole().name();
            }
        }
        return ProjectMemberVo.Role.visitor.name();
    }

    private List<SystemLogVo> recentLogs(String projectId) {
        try {
            return systemLogService.getSystemLog(projectId, 0, 10);
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private ProjectSummary toProjectSummary(ProjectVo project) {
        ProjectSummary summary = new ProjectSummary();
        summary.setId(project.getId());
        summary.setName(project.getName());
        summary.setDescribe(project.getDescribe());
        summary.setCreate(project.getCreate());
        summary.setCreateDisplayName(project.getCreateDisplayName());
        summary.setMemberCount(project.getMemberCount());
        summary.setCreateTime(project.getCreateTime());
        summary.setUpdateTime(project.getUpdateTime());
        return summary;
    }

    private AppSummary toAppSummary(AppVo app) {
        AppSummary summary = new AppSummary();
        summary.setId(app.getId());
        summary.setName(app.getName());
        summary.setSrcName(app.getSrcName());
        summary.setLanguage(app.getLanguage());
        summary.setLanguageConfig(app.getLanguageConfig());
        summary.setDescribe(app.getDescribe());
        summary.setRange(app.getRange());
        summary.setOnlineCount(app.getOnlineCount());
        summary.setCurrentVersion(app.getCurrentVersion());
        summary.setCurrentBranch(app.getCurrentBranch());
        summary.setCurrentCommitId(app.getCurrentCommitId());
        summary.setRepoConfigured(StringUtils.hasText(app.getRepoAddress()));
        summary.setSourceType(app.getLanguage());
        return summary;
    }

    private UserSummary toUserSummary(UserVo user) {
        UserSummary summary = new UserSummary();
        summary.setId(user.getId());
        summary.setName(user.getName());
        summary.setNickname(user.getNickname());
        summary.setEmail(user.getEmail());
        summary.setHeader(user.getHeader());
        summary.setPhone(user.getPhone());
        summary.setReadme(user.getReadme());
        return summary;
    }

    private <T> ResultNotified<T> ok(String message, T data) {
        return new ResultNotified<>(true, message, data);
    }
}
