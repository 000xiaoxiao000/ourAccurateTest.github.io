package com.oAT.web.control.api;

import com.oAT.web.api.common.ApiSummaries.*;
import com.oAT.web.api.project.ProjectSettingsApiPayloads.*;
import com.oAT.web.control.entity.ResultNotified;
import com.oAT.web.esDao.entity.App;
import com.oAT.web.esDao.entity.LabelGroup;
import com.oAT.web.service.AppService;
import com.oAT.web.service.ProjectService;
import com.oAT.web.service.UserService;
import com.oAT.web.service.entity.AppVo;
import com.oAT.web.service.entity.LabelGroupVo;
import com.oAT.web.service.entity.LableType;
import com.oAT.web.service.entity.ProjectMemberVo;
import com.oAT.web.service.entity.ProjectVo;
import com.oAT.web.service.entity.UserVo;
import org.springframework.util.Assert;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/projects/{projectId}")
public class ProjectSettingsApiControl {

    private final ProjectService projectService;
    private final AppService appService;
    private final UserService userService;

    public ProjectSettingsApiControl(ProjectService projectService,
                                     AppService appService,
                                     UserService userService) {
        this.projectService = projectService;
        this.appService = appService;
        this.userService = userService;
    }

    @GetMapping("/apps")
    public ResultNotified<List<AppSummary>> apps(@PathVariable String projectId,
                                                                           @SessionAttribute UserVo user) {
        ensureProjectAccess(projectId, user);
        List<AppVo> apps = appService.getAppList(projectId);
        List<AppSummary> result = new ArrayList<>();
        for (AppVo app : apps) {
            result.add(toAppSummary(app));
        }
        return new ResultNotified<>(true, "获取应用列表成功", result);
    }

    @PostMapping("/apps")
    public ResultNotified<AppSummary> createApp(@PathVariable String projectId,
                                                                          @SessionAttribute UserVo user,
                                                                          @RequestBody SaveAppRequest request) {
        ensureProjectAccess(projectId, user);
        Assert.notNull(request, "请求体不能为空");
        Assert.hasText(request.getName(), "应用名称不能为空");

        App app = new App();
        app.setCreateProjectId(projectId);
        app.setCreateUserId(user.getId());
        app.setName(request.getName().trim());
        app.setSrcName(request.getSrcName());
        app.setLanguage(request.getLanguage());
        app.setLanguageConfig(request.getLanguageConfig());
        app.setRange(request.getRange());
        app.setDescribe(request.getDescribe());
        app.setProperties(request.getProperties());
        app.setCurrentVersion(request.getCurrentVersion());
        app.setCurrentBranch(request.getCurrentBranch());
        app.setCurrentCommitId(request.getCurrentCommitId());

        AppVo created = appService.createApp(app);
        return new ResultNotified<>(true, "应用创建成功", toAppSummary(created));
    }

    @PostMapping("/apps/{appId}/delete")
    public ResultNotified<String> deleteApp(@PathVariable String projectId,
                                            @PathVariable String appId,
                                            @SessionAttribute UserVo user,
                                            @RequestBody DeleteAppRequest request) {
        ensureProjectAccess(projectId, user);
        Assert.notNull(request, "请求体不能为空");
        Assert.hasText(request.getPassword(), "删除密码不能为空");
        String md5Pwd = DigestUtils.md5DigestAsHex(request.getPassword().getBytes(StandardCharsets.UTF_8));
        Assert.isTrue(user.getPassword().equalsIgnoreCase(md5Pwd), "删除失败!密码错误");
        appService.deleteApp(projectId, appId);
        return new ResultNotified<>(true, "应用已经被删除", appId);
    }

    @GetMapping("/members")
    public ResultNotified<ProjectMembersPayload> members(@PathVariable String projectId,
                                                         @SessionAttribute UserVo user) {
        ensureProjectAccess(projectId, user);
        List<ProjectMemberVo> members = projectService.getProjectMembers(projectId);
        List<UserVo> users = new ArrayList<>(userService.getAllUser());
        Set<String> existingMemberIds = new HashSet<>();
        List<ProjectMemberSummary> memberSummaries = new ArrayList<>();

        for (ProjectMemberVo member : members) {
            existingMemberIds.add(member.getMemberId());
            UserVo profile = findUser(users, member.getMemberId());
            if (profile != null) {
                member.setMemberName(profile.getName());
                member.setMemberEmail(profile.getEmail());
            }
            memberSummaries.add(toProjectMemberSummary(member));
        }

        List<UserSummary> availableUsers = new ArrayList<>();
        for (UserVo candidate : users) {
            if (!existingMemberIds.contains(candidate.getId())) {
                availableUsers.add(toUserSummary(candidate));
            }
        }

        ProjectMembersPayload payload = new ProjectMembersPayload();
        payload.setMembers(memberSummaries);
        payload.setAvailableUsers(availableUsers);
        payload.setCurrentUserRole(resolveUserRole(projectId, user));
        return new ResultNotified<>(true, "获取项目成员成功", payload);
    }

    @PostMapping("/members/add")
    public ResultNotified<ProjectMembersPayload> addMembers(@PathVariable String projectId,
                                                            @SessionAttribute UserVo user,
                                                            @RequestBody AddMembersRequest request) {
        ensureProjectAccess(projectId, user);
        Assert.notNull(request, "请求体不能为空");
        Assert.notEmpty(request.getUserIds(), "待添加用户不能为空");
        for (String userId : request.getUserIds()) {
            if (StringUtils.hasText(userId)) {
                projectService.addProjectMember(projectId, userId);
            }
        }
        return members(projectId, user);
    }

    @PostMapping("/members/{projectMemberId}/remove")
    public ResultNotified<ProjectMembersPayload> removeMember(@PathVariable String projectId,
                                                              @PathVariable String projectMemberId,
                                                              @SessionAttribute UserVo user) {
        ensureProjectAccess(projectId, user);
        projectService.deleteProjectMember(projectId, projectMemberId);
        return members(projectId, user);
    }

    @PostMapping("/members/{projectMemberId}/role")
    public ResultNotified<ProjectMembersPayload> updateMemberRole(@PathVariable String projectId,
                                                                  @PathVariable String projectMemberId,
                                                                  @SessionAttribute UserVo user,
                                                                  @RequestBody UpdateMemberRoleRequest request) {
        ensureProjectAccess(projectId, user);
        Assert.notNull(request, "请求体不能为空");
        Assert.hasText(request.getRole(), "role 不能为空");
        projectService.updateProjectMemberRole(projectId, projectMemberId, ProjectMemberVo.Role.valueOf(request.getRole()));
        return members(projectId, user);
    }

    @GetMapping("/labels")
    public ResultNotified<ProjectLabelsPayload> labels(@PathVariable String projectId,
                                                       @SessionAttribute UserVo user) {
        ensureProjectAccess(projectId, user);
        ProjectLabelsPayload payload = new ProjectLabelsPayload();
        payload.setUsecaseLabels(toLabelSummaries(projectService.getLables(projectId, LableType.usecase)));
        payload.setCurrentUserRole(resolveUserRole(projectId, user));
        return new ResultNotified<>(true, "获取标签成功", payload);
    }

    @PostMapping("/labels/upsert")
    public ResultNotified<ProjectLabelsPayload> upsertLabel(@PathVariable String projectId,
                                                            @SessionAttribute UserVo user,
                                                            @RequestBody UpsertLabelRequest request) {
        ensureProjectAccess(projectId, user);
        Assert.notNull(request, "请求体不能为空");
        Assert.hasText(request.getType(), "type 不能为空");
        Assert.hasText(request.getName(), "name 不能为空");

        String color = StringUtils.hasText(request.getColor()) ? request.getColor() : "grey";
        LabelGroup.Label newLabel = new LabelGroup.Label(request.getName(), color);
        List<LabelGroupVo> groups = projectService.getLableGroup(projectId, LableType.valueOf(request.getType()));

        if (groups.isEmpty()) {
            LabelGroupVo groupVo = new LabelGroupVo();
            groupVo.setProjectid(projectId);
            groupVo.setType(request.getType());
            groupVo.setGroupName(request.getType() + " label");
            groupVo.setLabels(new LabelGroup.Label[]{newLabel});
            projectService.doSaveLabelGroup(groupVo);
        } else {
            LabelGroupVo groupVo = groups.get(0);
            List<LabelGroup.Label> labels = new ArrayList<>(Arrays.asList(groupVo.getLabels()));
            boolean updated = false;
            for (LabelGroup.Label label : labels) {
                if (request.getName().equals(label.getName())) {
                    label.setColor(color);
                    updated = true;
                    break;
                }
            }
            if (!updated) {
                labels.add(newLabel);
            }
            groupVo.setLabels(labels.toArray(new LabelGroup.Label[0]));
            projectService.doSaveLabelGroup(groupVo);
        }

        return labels(projectId, user);
    }

    @PostMapping("/labels/delete")
    public ResultNotified<ProjectLabelsPayload> deleteLabel(@PathVariable String projectId,
                                                            @SessionAttribute UserVo user,
                                                            @RequestBody DeleteLabelRequest request) {
        ensureProjectAccess(projectId, user);
        Assert.notNull(request, "请求体不能为空");
        Assert.hasText(request.getType(), "type 不能为空");
        Assert.hasText(request.getName(), "name 不能为空");

        List<LabelGroupVo> groups = projectService.getLableGroup(projectId, LableType.valueOf(request.getType()));
        Assert.isTrue(!groups.isEmpty(), "找不到标签组");

        LabelGroupVo groupVo = groups.get(0);
        List<LabelGroup.Label> labels = new ArrayList<>(Arrays.asList(groupVo.getLabels()));
        labels.removeIf(label -> request.getName().equals(label.getName()));
        groupVo.setLabels(labels.toArray(new LabelGroup.Label[0]));
        projectService.doSaveLabelGroup(groupVo);

        return labels(projectId, user);
    }

    private ProjectVo ensureProjectAccess(String projectId, UserVo user) {
        ProjectVo project = projectService.getProjectByProjectIdAndMemberId(projectId, user.getId());
        Assert.notNull(project, "找不到指定项目,或者您没有该项目的访问权限");
        return project;
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
        summary.setRepoConfigured(app.getRepoAddress() != null && !app.getRepoAddress().trim().isEmpty());
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

    private ProjectMemberSummary toProjectMemberSummary(ProjectMemberVo member) {
        ProjectMemberSummary summary = new ProjectMemberSummary();
        summary.setId(member.getId());
        summary.setProjectId(member.getProjectId());
        summary.setMemberId(member.getMemberId());
        summary.setMemberName(member.getMemberName());
        summary.setMemberEmail(member.getMemberEmail());
        summary.setRole(member.getRole() == null ? null : member.getRole().name());
        summary.setStar(Boolean.TRUE.equals(member.getStar()));
        summary.setDefaultProject(Boolean.TRUE.equals(member.getDefaultProject()));
        summary.setCreateTime(member.getCreateTime());
        return summary;
    }

    private List<LabelSummary> toLabelSummaries(List<LabelGroup.Label> labels) {
        List<LabelSummary> result = new ArrayList<>();
        for (LabelGroup.Label label : labels) {
            LabelSummary summary = new LabelSummary();
            summary.setName(label.getName());
            summary.setColor(label.getColor());
            result.add(summary);
        }
        return result;
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

    private UserVo findUser(List<UserVo> users, String userId) {
        for (UserVo user : users) {
            if (userId.equals(user.getId())) {
                return user;
            }
        }
        return null;
    }

}
