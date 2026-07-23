package com.oAT.web.control.api;

import com.oAT.web.api.version.VersionApiPayloads.*;
import com.oAT.web.api.version.VersionCenterPayloadService;
import com.oAT.web.api.version.VersionGitWorkflowService;
import com.oAT.web.api.version.VersionReportDetailService;
import com.oAT.web.control.entity.ResultNotified;
import com.oAT.web.persistence.entity.SystemLog;
import com.oAT.web.service.AppService;
import com.oAT.web.service.ProjectService;
import com.oAT.web.service.GitService;
import com.oAT.web.service.ResourceService;
import com.oAT.web.service.SystemLogService;
import com.oAT.web.service.VersionService;
import com.oAT.web.service.entity.AppVo;
import com.oAT.web.service.entity.CompareJobVo;
import com.oAT.web.service.entity.GitCommitOptionVo;
import com.oAT.web.service.entity.GitJobVo;
import com.oAT.web.service.entity.GitPullEstimateVo;
import com.oAT.web.service.entity.PackageCommitVerifyVo;
import com.oAT.web.service.entity.ProjectMemberVo;
import com.oAT.web.service.entity.ProjectVo;
import com.oAT.web.service.entity.UserVo;
import com.oAT.web.service.entity.VersionItemVo;
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

import java.io.File;
import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/api/projects/{projectId}")
public class VersionApiControl {

    private final VersionService versionService;
    private final AppService appService;
    private final ProjectService projectService;
    private final VersionCenterPayloadService versionCenterPayloadService;
    private final VersionReportDetailService versionReportDetailService;
    private final GitService gitService;
    private final ResourceService resourceService;
    private final SystemLogService systemLogService;
    private final VersionGitWorkflowService versionGitWorkflowService;

    public VersionApiControl(VersionService versionService,
                             AppService appService,
                             ProjectService projectService,
                             VersionCenterPayloadService versionCenterPayloadService,
                             VersionReportDetailService versionReportDetailService,
                             GitService gitService,
                             ResourceService resourceService,
                             SystemLogService systemLogService,
                             VersionGitWorkflowService versionGitWorkflowService) {
        this.versionService = versionService;
        this.appService = appService;
        this.projectService = projectService;
        this.versionCenterPayloadService = versionCenterPayloadService;
        this.versionReportDetailService = versionReportDetailService;
        this.gitService = gitService;
        this.resourceService = resourceService;
        this.systemLogService = systemLogService;
        this.versionGitWorkflowService = versionGitWorkflowService;
    }

    @GetMapping("/apps/{appId}/version-center")
    public ResultNotified<VersionCenterPayload> versionCenter(@PathVariable String projectId,
                                                              @PathVariable String appId,
                                                              @SessionAttribute UserVo user) {
        ensureProjectAccess(projectId, user);
        AppVo app = appService.getApp(appId);
        Assert.notNull(app, "应用不存在");
        VersionCenterPayload payload = versionCenterPayloadService.buildVersionCenter(
                projectId,
                appId,
                app,
                versionCenterPayloadService.resolveUserRole(projectService.getProjectMembers(projectId), user));
        return new ResultNotified<>(true, "获取版本中心数据成功", payload);
    }


    @PostMapping("/apps/{appId}/versions")
    public ResultNotified<String> createVersion(@PathVariable String projectId,
                                                @PathVariable String appId,
                                                @SessionAttribute UserVo user,
                                                VersionItemVo itemVo) {
        ensureProjectAccess(projectId, user);
        itemVo.setProjectId(projectId);
        itemVo.setAppId(appId);
        if ("git".equals(itemVo.getSourceType())) {
            VersionItemVo existing = versionService.getVersionByGitInfo(appId, itemVo.getVersionNumber(), itemVo.getRepoBranch(), itemVo.getRepoCommitId());
            if (existing != null) {
                return new ResultNotified<>(false, "该版本号下已存在相同的分支和 CommitID");
            }
            if (StringUtils.hasText(itemVo.getProgramFile())) {
                File programFile = new File(itemVo.getProgramFile());
                if (!programFile.exists()) {
                    programFile = new File(resourceService.getCacheRoot(), itemVo.getProgramFile());
                }
                if (programFile.exists() && !StringUtils.hasText(itemVo.getProgramName())) {
                    itemVo.setProgramName(programFile.getName());
                }
            }
        }
        versionService.addVersionItem(itemVo);
        if ("on".equals(itemVo.getSetAsCurrent())) {
            AppVo app = appService.getApp(appId);
            app.setCurrentVersion(itemVo.getVersionNumber());
            app.setCurrentBranch(itemVo.getRepoBranch());
            app.setCurrentCommitId(itemVo.getRepoCommitId());
            appService.updateApp(projectId, app);
        }
        return new ResultNotified<>(true, "版本创建成功");
    }

    @PostMapping("/apps/{appId}/versions/current")
    public ResultNotified<String> setCurrentVersion(@PathVariable String projectId,
                                                    @PathVariable String appId,
                                                    @SessionAttribute UserVo user,
                                                    @RequestParam String versionNumber,
                                                    @RequestParam(required = false) String branch,
                                                    @RequestParam(required = false) String commitId) {
        ensureProjectAccess(projectId, user);
        if (isVisitor(projectId, user)) {
            return new ResultNotified<>(false, "没有权限进行此操作");
        }
        AppVo app = appService.getApp(appId);
        Assert.notNull(app, "应用不存在");
        String oldVersionText = String.format("[%s (分支:%s, Commit:%s)]",
                app.getCurrentVersion() != null ? app.getCurrentVersion() : "未设置",
                app.getCurrentBranch() != null ? app.getCurrentBranch() : "-",
                abbreviateCommit(app.getCurrentCommitId()));
        String newVersionText = String.format("[%s (分支:%s, Commit:%s)]",
                versionNumber, branch != null ? branch : "-", abbreviateCommit(commitId));
        
        app.setCurrentVersion(versionNumber);
        app.setCurrentBranch(branch);
        app.setCurrentCommitId(commitId);
        appService.updateApp(projectId, app);
        SystemLog log = new SystemLog();
        log.setTitle(String.format("%s 将应用 %s 的当前版本从 %s 变更为 %s", user.getName(), app.getName(), oldVersionText, newVersionText));
        log.setUserId(user.getId());
        log.setUserName(user.getName());
        log.setProjectId(projectId);
        log.setAction(SystemLogService.Action.editApp.toString());
        systemLogService.addLog(log);
        
        return new ResultNotified<>(true, "设置当前版本成功");
    }

    @PostMapping("/apps/{appId}/versions/delete")
    public ResultNotified<String> deleteVersion(@PathVariable String projectId,
                                                @PathVariable String appId,
                                                @SessionAttribute UserVo user,
                                                @RequestParam String id) {
        ensureProjectAccess(projectId, user);
        versionService.doDeleteVersionItem(id);
        return new ResultNotified<>(true, "版本项目删除成功");
    }

    @PostMapping("/apps/{appId}/version-reports/delete")
    public ResultNotified<String> deleteCompareReport(@PathVariable String projectId,
                                                      @PathVariable String appId,
                                                      @SessionAttribute UserVo user,
                                                      @RequestParam String reportId) {
        ensureProjectAccess(projectId, user);
        versionService.deleteCompareReport(projectId, reportId);
        return new ResultNotified<>(true, "报告删除成功");
    }

    @GetMapping("/apps/{appId}/version-files/delete")
    public ResultNotified<String> deleteVersionFile(@PathVariable String projectId,
                                                    @PathVariable String appId,
                                                    @SessionAttribute UserVo user,
                                                    @RequestParam String filePath) {
        ensureProjectAccess(projectId, user);
        versionService.deleteCacheFile(filePath);
        return new ResultNotified<>(true, "本地文件已删除", filePath);
    }

    @GetMapping("/apps/{appId}/git/pull-check")
    public ResultNotified<GitPullEstimateVo> checkGitPull(@PathVariable String projectId,
                                                          @PathVariable String appId,
                                                          @SessionAttribute UserVo user,
                                                          @RequestParam(required = false) String branch,
                                                          @RequestParam(required = false) String commitId,
                                                          @RequestParam(required = false) String versionNumber,
                                                          @RequestParam(required = false) String excludePaths) {
        ensureProjectAccess(projectId, user);
        try {
            GitPullEstimateVo estimate = versionGitWorkflowService.checkGitPull(appId, branch, commitId, versionNumber, excludePaths);
            return new ResultNotified<>(true, "检测通过", estimate);
        } catch (Exception e) {
            return new ResultNotified<>(false, "检测失败: " + e.getMessage(), null);
        }
    }

    @GetMapping("/apps/{appId}/git/latest-commit")
    public ResultNotified<String> latestCommit(@PathVariable String projectId,
                                               @PathVariable String appId,
                                               @SessionAttribute UserVo user,
                                               @RequestParam String branch) {
        ensureProjectAccess(projectId, user);
        try {
            AppVo app = appService.getApp(appId);
            Assert.notNull(app, "应用不存在");
            Assert.isTrue(StringUtils.hasText(app.getRepoAddress()), "Git仓库地址未配置");
            return new ResultNotified<>(true, "获取成功", gitService.getLatestCommitId(app.getRepoAddress(), app.getRepoUserName(), app.getRepoPassword(), branch));
        } catch (Exception e) {
            return new ResultNotified<>(false, e.getMessage(), null);
        }
    }

    @GetMapping("/apps/{appId}/git/commits")
    public ResultNotified<List<GitCommitOptionVo>> commits(@PathVariable String projectId,
                                                           @PathVariable String appId,
                                                           @SessionAttribute UserVo user,
                                                           @RequestParam String branch,
                                                           @RequestParam(defaultValue = "20") int limit) {
        ensureProjectAccess(projectId, user);
        try {
            AppVo app = appService.getApp(appId);
            Assert.notNull(app, "应用不存在");
            Assert.isTrue(StringUtils.hasText(app.getRepoAddress()), "Git仓库地址未配置");
            return new ResultNotified<>(true, "获取成功", gitService.getRecentCommits(app.getRepoAddress(), app.getRepoUserName(), app.getRepoPassword(), branch, limit));
        } catch (Exception e) {
            return new ResultNotified<>(false, e.getMessage(), Collections.emptyList());
        }
    }

    @PostMapping("/apps/{appId}/git/pull")
    public ResultNotified<String> startGitPull(@PathVariable String projectId,
                                               @PathVariable String appId,
                                               @SessionAttribute UserVo user,
                                               @RequestParam(required = false) String branch,
                                               @RequestParam(required = false) String commitId,
                                               @RequestParam(required = false) String excludePaths,
                                               @RequestParam(required = false) String versionNumber) {
        ensureProjectAccess(projectId, user);
        try {
            String jobId = versionGitWorkflowService.startGitPull(appId, branch, commitId, excludePaths, versionNumber);
            return new ResultNotified<>(true, "开始拉取", jobId);
        } catch (Exception e) {
            return new ResultNotified<>(false, "远程代码拉取失败: " + e.getMessage(), null);
        }
    }

    @GetMapping("/apps/{appId}/git/jobs/{jobId}")
    public ResultNotified<GitJobVo> gitPullStatus(@PathVariable String projectId,
                                                  @PathVariable String appId,
                                                  @PathVariable String jobId,
                                                  @SessionAttribute UserVo user) {
        ensureProjectAccess(projectId, user);
        GitJobVo job = gitService.getGitJob(jobId);
        return job == null ? new ResultNotified<>(false, "任务不存在", null) : new ResultNotified<>(true, "查询成功", job);
    }

    @GetMapping("/apps/{appId}/git/cache")
    public ResultNotified<String> deleteGitCache(@PathVariable String projectId,
                                                 @PathVariable String appId,
                                                 @SessionAttribute UserVo user,
                                                 @RequestParam String cachePath) {
        ensureProjectAccess(projectId, user);
        gitService.deleteCache(cachePath);
        return new ResultNotified<>(true, "删除成功");
    }

    @GetMapping("/apps/{appId}/packages/commit-verify")
    public ResultNotified<PackageCommitVerifyVo> verifyPackageCommit(@PathVariable String projectId,
                                                                     @PathVariable String appId,
                                                                     @SessionAttribute UserVo user,
                                                                     @RequestParam String programFile,
                                                                     @RequestParam(required = false) String commitId) {
        ensureProjectAccess(projectId, user);
        try {
            return new ResultNotified<>(true, "校验完成", versionGitWorkflowService.verifyUploadedPackageCommit(appId, programFile, commitId));
        } catch (Exception e) {
            return new ResultNotified<>(false, "校验失败: " + e.getMessage(), null);
        }
    }

    @PostMapping("/apps/{appId}/compare-jobs")
    public ResultNotified<CompareJobPayload> startCompare(@PathVariable String projectId,
                                                          @PathVariable String appId,
                                                          @SessionAttribute UserVo user,
                                                          @RequestBody StartCompareRequest request) {
        ensureProjectAccess(projectId, user);
        AppVo app = appService.getApp(appId);
        Assert.notNull(app, "应用不存在");

        String packageName = StringUtils.hasText(request.getPackageName()) ? request.getPackageName() : "*";
        String jobId;
        if ("git".equalsIgnoreCase(request.getMode())) {
            Assert.hasText(request.getBranch(), "branch不能为空");
            Assert.hasText(request.getOldCommit(), "oldCommit不能为空");
            Assert.hasText(request.getNewCommit(), "newCommit不能为空");
            jobId = versionService.startCompareFromGit(projectId, app, packageName,
                    request.getBranch(), request.getOldCommit(), request.getNewCommit());
        } else {
            Assert.hasText(request.getSourceFile(), "sourceFile不能为空");
            Assert.hasText(request.getTargetFile(), "targetFile不能为空");
            jobId = versionService.startCompareJob(projectId, app, packageName,
                    request.getSourceFile(), request.getTargetFile());
        }
        Assert.hasText(jobId, "比对任务启动失败");
        CompareJobVo job = versionService.getCompareJob(jobId);
        CompareJobPayload payload = new CompareJobPayload();
        payload.setJobId(jobId);
        payload.setJob(job == null ? null : versionCenterPayloadService.toCompareJobSummary(job));
        return new ResultNotified<>(true, "比对任务已启动", payload);
    }

    @GetMapping("/apps/{appId}/compare-jobs/{jobId}")
    public ResultNotified<CompareJobSummary> compareJob(@PathVariable String projectId,
                                                        @PathVariable String appId,
                                                        @PathVariable String jobId,
                                                        @SessionAttribute UserVo user) {
        ensureProjectAccess(projectId, user);
        AppVo app = appService.getApp(appId);
        Assert.notNull(app, "应用不存在");
        CompareJobVo job = versionService.getCompareJob(jobId);
        Assert.notNull(job, "比对任务不存在");
        return new ResultNotified<>(true, "获取比对任务成功", versionCenterPayloadService.toCompareJobSummary(job));
    }

    @GetMapping("/version/reports/{reportId}")
    public ResultNotified<VersionReportDetailPayload> compareReport(@PathVariable String projectId,
                                                                    @PathVariable String reportId,
                                                                    @SessionAttribute UserVo user) {
        ensureProjectAccess(projectId, user);
        try {
            return new ResultNotified<>(true, "获取比对报告成功",
                    versionReportDetailService.buildReadyPayload(versionService.getCompareReport(reportId)));
        } catch (IllegalArgumentException ex) {
            return new ResultNotified<>(true, "比对报告暂未就绪",
                    versionReportDetailService.buildPendingPayload(versionService.getCompareJob(reportId)));
        }
    }

    private ProjectVo ensureProjectAccess(String projectId, UserVo user) {
        ProjectVo project = projectService.getProjectByProjectIdAndMemberId(projectId, user.getId());
        Assert.notNull(project, "找不到指定项目,或者您没有该项目的访问权限");
        return project;
    }

    private boolean isVisitor(String projectId, UserVo user) {
        return projectService.getProjectMembers(projectId).stream()
                .filter(member -> user.getName().equals(member.getMemberName()))
                .map(ProjectMemberVo::getRole)
                .anyMatch(role -> ProjectMemberVo.Role.visitor.equals(role));
    }

    private String abbreviateCommit(String commitId) {
        if (!StringUtils.hasText(commitId)) {
            return "-";
        }
        return commitId.length() > 7 ? commitId.substring(0, 7) : commitId;
    }

}
