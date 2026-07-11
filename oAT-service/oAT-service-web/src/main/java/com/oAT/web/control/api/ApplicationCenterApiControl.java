package com.oAT.web.control.api;

import com.oAT.web.api.common.ApiSummaries.*;
import com.oAT.web.api.app.ApplicationCenterApiPayloads.*;
import com.oAT.web.collector.CollectorSource;
import com.oAT.web.collector.CollectorSourceService;
import com.oAT.web.control.entity.ResultNotified;
import com.oAT.web.service.AppService;
import com.oAT.web.service.GitService;
import com.oAT.web.service.ProjectService;
import com.oAT.web.service.entity.AppVo;
import com.oAT.web.service.entity.ProjectMemberVo;
import com.oAT.web.service.entity.ProjectVo;
import com.oAT.web.service.entity.UserVo;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/api/projects/{projectId}")
public class ApplicationCenterApiControl {

    private final AppService appService;
    private final ProjectService projectService;
    private final GitService gitService;
    private final CollectorSourceService collectorSourceService;

    public ApplicationCenterApiControl(AppService appService,
                                       ProjectService projectService,
                                       GitService gitService,
                                       CollectorSourceService collectorSourceService) {
        this.appService = appService;
        this.projectService = projectService;
        this.gitService = gitService;
        this.collectorSourceService = collectorSourceService;
    }

    @GetMapping("/collector-sources")
    public ResultNotified<CollectorSourcesPayload> collectorSources(@PathVariable String projectId,
                                                                    @SessionAttribute UserVo user) {
        ensureProjectAccess(projectId, user);
        List<CollectorSource> sources = collectorSourceService.listProjectSources(projectId);
        CollectorSourcesPayload payload = new CollectorSourcesPayload();
        payload.setSources(sources);
        payload.setTotal(sources.size());
        payload.setCurrentUserRole(resolveUserRole(projectId, user));
        return new ResultNotified<>(true, "获取采集源状态成功", payload);
    }

    @GetMapping("/apps/{appId}/settings")
    public ResultNotified<AppSettingsPayload> appSettings(@PathVariable String projectId,
                                                          @PathVariable String appId,
                                                          @SessionAttribute UserVo user) {
        ensureProjectAccess(projectId, user);
        AppVo app = appService.getApp(appId);
        Assert.notNull(app, "应用不存在");
        AppSettingsPayload payload = new AppSettingsPayload();
        payload.setApp(toAppSettingsSummary(app));
        payload.setApps(toAppSummaries(appService.getAppList(projectId)));
        payload.setCurrentUserRole(resolveUserRole(projectId, user));
        return new ResultNotified<>(true, "获取应用设置成功", payload);
    }

    @PostMapping("/apps/{appId}/settings")
    public ResultNotified<AppSettingsPayload> saveAppSettings(@PathVariable String projectId,
                                                              @PathVariable String appId,
                                                              @SessionAttribute UserVo user,
                                                              @RequestBody SaveAppSettingsRequest request) {
        ensureProjectAccess(projectId, user);
        AppVo existingApp = appService.getApp(appId);
        Assert.notNull(existingApp, "应用不存在");
        Assert.isTrue(appId.equals(existingApp.getId()), "参数非法");

        existingApp.setName(request.getName());
        existingApp.setSrcName(request.getSrcName());
        existingApp.setLanguage(request.getLanguage());
        existingApp.setLanguageConfig(request.getLanguageConfig());
        existingApp.setRange(request.getRange());
        existingApp.setDescribe(request.getDescribe());
        existingApp.setProperties(request.getProperties());
        existingApp.setCurrentVersion(request.getCurrentVersion());
        existingApp.setCurrentBranch(request.getCurrentBranch());
        existingApp.setCurrentCommitId(request.getCurrentCommitId());

        appService.updateApp(projectId, existingApp);
        return appSettings(projectId, appId, user);
    }

    @GetMapping("/apps/{appId}/repository")
    public ResultNotified<RepositoryConfigPayload> repository(@PathVariable String projectId,
                                                              @PathVariable String appId,
                                                              @SessionAttribute UserVo user) {
        ensureProjectAccess(projectId, user);
        AppVo app = appService.getApp(appId);
        Assert.notNull(app, "应用不存在");
        RepositoryConfigPayload payload = new RepositoryConfigPayload();
        payload.setApp(toRepositorySummary(app));
        payload.setCurrentUserRole(resolveUserRole(projectId, user));
        return new ResultNotified<>(true, "获取仓库配置成功", payload);
    }

    @PostMapping("/apps/{appId}/repository")
    public ResultNotified<RepositoryConfigPayload> saveRepository(@PathVariable String projectId,
                                                                  @PathVariable String appId,
                                                                  @SessionAttribute UserVo user,
                                                                  @RequestBody SaveRepositoryRequest request) {
        ensureProjectAccess(projectId, user);
        AppVo existingApp = appService.getApp(appId);
        Assert.notNull(existingApp, "应用不存在");
        existingApp.setRepoAddress(request.getRepoAddress());
        existingApp.setRepoUserName(request.getRepoUserName());
        existingApp.setRepoPassword(request.getRepoPassword());
        appService.updateApp(projectId, existingApp);
        return repository(projectId, appId, user);
    }

    @GetMapping("/apps/{appId}/repository/branches")
    public ResultNotified<List<String>> repositoryBranches(@PathVariable String projectId,
                                                           @PathVariable String appId,
                                                           @SessionAttribute UserVo user) {
        ensureProjectAccess(projectId, user);
        try {
            AppVo app = appService.getApp(appId);
            Assert.notNull(app, "应用不存在");
            Assert.isTrue(StringUtils.hasText(app.getRepoAddress()), "Git仓库地址未配置");
            List<String> branches = gitService.getRemoteBranches(app.getRepoAddress(), app.getRepoUserName(), app.getRepoPassword());
            return new ResultNotified<>(true, "获取分支成功", branches);
        } catch (Exception e) {
            return new ResultNotified<>(false, e.getMessage(), Collections.emptyList());
        }
    }

    @GetMapping("/repository/branches")
    public ResultNotified<List<String>> repositoryBranchesPreview(@PathVariable String projectId,
                                                                  @SessionAttribute UserVo user,
                                                                  @RequestParam String repoUrl,
                                                                  @RequestParam(required = false) String username,
                                                                  @RequestParam(required = false) String password) {
        ensureProjectAccess(projectId, user);
        try {
            Assert.isTrue(StringUtils.hasText(repoUrl), "Git仓库地址不能为空");
            List<String> branches = gitService.getRemoteBranches(repoUrl, username, password);
            return new ResultNotified<>(true, "获取分支成功", branches);
        } catch (Exception e) {
            return new ResultNotified<>(false, e.getMessage(), Collections.emptyList());
        }
    }

    private ProjectVo ensureProjectAccess(String projectId, UserVo user) {
        ProjectVo project = projectService.getProjectByProjectIdAndMemberId(projectId, user.getId());
        Assert.notNull(project, "找不到指定项目,或者您没有该项目的访问权限");
        return project;
    }

    private String resolveUserRole(String projectId, UserVo user) {
        List<ProjectMemberVo> members = projectService.getProjectMembers(projectId);
        for (ProjectMemberVo member : members) {
            if (user.getName() != null && user.getName().equals(member.getMemberName()) && member.getRole() != null) {
                return member.getRole().name();
            }
        }
        return ProjectMemberVo.Role.visitor.name();
    }

    private List<AppSummary> toAppSummaries(List<AppVo> apps) {
        List<AppSummary> result = new ArrayList<>();
        for (AppVo app : apps) {
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
            result.add(summary);
        }
        return result;
    }

    private AppSettingsSummary toAppSettingsSummary(AppVo app) {
        AppSettingsSummary summary = new AppSettingsSummary();
        summary.setId(app.getId());
        summary.setName(app.getName());
        summary.setSrcName(app.getSrcName());
        summary.setLanguage(app.getLanguage());
        summary.setLanguageConfig(app.getLanguageConfig());
        summary.setRange(app.getRange());
        summary.setDescribe(app.getDescribe());
        summary.setProperties(app.getProperties());
        summary.setCurrentVersion(app.getCurrentVersion());
        summary.setCurrentBranch(app.getCurrentBranch());
        summary.setCurrentCommitId(app.getCurrentCommitId());
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

    private RepositorySummary toRepositorySummary(AppVo app) {
        RepositorySummary summary = new RepositorySummary();
        summary.setAppId(app.getId());
        summary.setAppName(app.getName());
        summary.setRepoAddress(app.getRepoAddress());
        summary.setRepoUserName(app.getRepoUserName());
        summary.setRepoPassword(app.getRepoPassword());
        summary.setConfigured(StringUtils.hasText(app.getRepoAddress()));
        return summary;
    }

}
