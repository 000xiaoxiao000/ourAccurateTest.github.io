package com.oAT.web.control.api;

import com.oAT.web.api.usecase.UsecaseApiPayloads.*;
import com.oAT.web.api.usecase.UsecasePayloadService;
import com.oAT.web.control.entity.DirectoryDeletePreview;
import com.oAT.web.control.entity.ResultNotified;
import com.oAT.web.service.ProjectService;
import com.oAT.web.service.UsecaseFileService;
import com.oAT.web.service.UsecaseService;
import com.oAT.web.service.entity.DirectoryDeleteResult;
import com.oAT.web.service.entity.ProjectVo;
import com.oAT.web.service.entity.UsecaseDirectoryVo;
import com.oAT.web.service.entity.UsecaseVo;
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
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projects/{projectId}/usecases")
public class UsecaseApiControl {

    private final UsecaseService usecaseService;
    private final UsecaseFileService usecaseFileService;
    private final ProjectService projectService;
    private final UsecasePayloadService usecasePayloadService;

    public UsecaseApiControl(UsecaseService usecaseService,
                             UsecaseFileService usecaseFileService,
                             ProjectService projectService,
                             UsecasePayloadService usecasePayloadService) {
        this.usecaseService = usecaseService;
        this.usecaseFileService = usecaseFileService;
        this.projectService = projectService;
        this.usecasePayloadService = usecasePayloadService;
    }

    @GetMapping
    public ResultNotified<UsecaseListPayload> list(@PathVariable String projectId,
                                                   @SessionAttribute UserVo user,
                                                   @RequestParam(required = false) String directory,
                                                   @RequestParam(required = false) String sort,
                                                   @RequestParam(required = false) String keyword) {
        ensureProjectAccess(projectId, user);
        UsecaseListPayload payload = usecasePayloadService.buildListPayload(projectId, user, directory, sort, keyword);
        return new ResultNotified<>(true, "获取用例列表成功", payload);
    }

    @GetMapping("/bootstrap")
    public ResultNotified<UsecaseBootstrapPayload> bootstrap(@PathVariable String projectId,
                                                             @SessionAttribute UserVo user,
                                                             @RequestParam(required = false) String directory,
                                                             @RequestParam(required = false) String id) {
        ensureProjectAccess(projectId, user);
        UsecaseBootstrapPayload payload = usecasePayloadService.buildBootstrapPayload(projectId, user, directory, id);
        return new ResultNotified<>(true, "获取用例编辑上下文成功", payload);
    }

    @GetMapping("/{id}")
    public ResultNotified<UsecaseDetailPayload> detail(@PathVariable String projectId,
                                                       @PathVariable String id,
                                                       @SessionAttribute UserVo user) {
        ensureProjectAccess(projectId, user);
        UsecaseDetailPayload payload = usecasePayloadService.buildDetailPayload(projectId, id, user);
        return new ResultNotified<>(true, "获取用例详情成功", payload);
    }

    @PostMapping("/save")
    public ResultNotified<String> save(@PathVariable String projectId,
                                       @SessionAttribute UserVo user,
                                       @RequestBody SaveUsecaseRequest request) {
        ensureProjectAccess(projectId, user);
        Assert.hasText(request.getTitle(), "用例标题不能为空");
        Assert.hasText(request.getDirectory(), "目录不能为空");

        UsecaseVo usecase = new UsecaseVo();
        usecase.setId(StringUtils.hasText(request.getId()) ? request.getId() : null);
        usecase.setProjectId(projectId);
        usecase.setTitle(request.getTitle());
        usecase.setHeadImage(request.getHeadImage());
        usecase.setContent(request.getContent());
        usecase.setDirectory(request.getDirectory());
        usecase.setLabels(normalizeArray(request.getLabels()));
        usecase.setDefects(parseMultiLine(request.getDefectsText()));
        usecase.setPrdRequirements(parseMultiLine(request.getPrdRequirementsText()));
        usecase.setDefectsText(request.getDefectsText());
        usecase.setPrdRequirementsText(request.getPrdRequirementsText());

        if (!StringUtils.hasText(request.getId())) {
            UsecaseVo created = usecaseService.doAdd(user.getId(), usecase);
            return new ResultNotified<>(true, "用例新增成功", created.getId());
        }
        usecaseService.doUpdate(user.getId(), usecase);
        return new ResultNotified<>(true, "用例保存成功", request.getId());
    }

    @PostMapping("/{id}/delete")
    public ResultNotified<String> delete(@PathVariable String projectId,
                                         @PathVariable String id,
                                         @SessionAttribute UserVo user) {
        ensureProjectAccess(projectId, user);
        usecaseService.doDeleteUsecase(projectId, id);
        return new ResultNotified<>(true, "用例删除成功", id);
    }

    @PostMapping("/{id}/share")
    public ResultNotified<String> updateShare(@PathVariable String projectId,
                                              @PathVariable String id,
                                              @SessionAttribute UserVo user,
                                              @RequestBody ShareUsecaseRequest request) {
        ensureProjectAccess(projectId, user);
        boolean enabled = Boolean.TRUE.equals(request.getShare());
        usecaseService.setShareState(projectId, user.getId(), id, enabled);
        return new ResultNotified<>(true, enabled ? "用例共享已开启" : "用例共享已关闭", id);
    }

    @GetMapping("/template/download")
    public void downloadTemplate(@PathVariable String projectId,
                                 @SessionAttribute UserVo user,
                                 HttpServletResponse response) throws IOException {
        ensureProjectAccess(projectId, user);
        usecaseFileService.downloadTemplate(response);
    }

    @PostMapping("/upload")
    public ResultNotified<?> uploadUsecases(@PathVariable String projectId,
                                            @SessionAttribute UserVo user,
                                            @RequestParam(required = false) String directory,
                                            @RequestParam("file") MultipartFile file) throws IOException {
        ensureProjectAccess(projectId, user);
        com.oAT.web.service.entity.UsecaseImportResult importResult = usecaseFileService.importUsecases(
                projectId,
                user.getId(),
                StringUtils.hasText(directory) ? directory : "root",
                file
        );
        if (importResult.hasErrors()) {
            ResultNotified<com.oAT.web.service.entity.UsecaseImportResult> result = new ResultNotified<>(false, "用例上传失败");
            result.setErrorMessage(buildImportErrorMessage(importResult));
            result.setData(importResult);
            return result;
        }
        ResultNotified<com.oAT.web.service.entity.UsecaseImportResult> result = new ResultNotified<>(true, "用例上传成功，导入 " + importResult.getSuccessCount() + " 条");
        result.setData(importResult);
        return result;
    }

    @GetMapping("/export")
    public void exportUsecases(@PathVariable String projectId,
                               @SessionAttribute UserVo user,
                               @RequestParam(required = false) String directory,
                               @RequestParam(required = false) String sort,
                               @RequestParam(required = false) String keyword,
                               HttpServletResponse response) throws IOException {
        ensureProjectAccess(projectId, user);
        usecaseFileService.exportUsecases(projectId, directory, sort, keyword, response);
    }

    @PostMapping("/directories/create")
    public ResultNotified<String> createDirectory(@PathVariable String projectId,
                                                  @SessionAttribute UserVo user,
                                                  @RequestBody CreateDirectoryRequest request) {
        ensureProjectAccess(projectId, user);
        Assert.hasText(request.getName(), "目录名称不能为空");
        String parentId = StringUtils.hasText(request.getParentId()) ? request.getParentId() : "root";
        UsecaseDirectoryVo created = usecaseService.createFolder(projectId, parentId, request.getName().trim());
        return new ResultNotified<>(true, "目录创建成功", created.getId());
    }

    @PostMapping("/directories/{directoryId}/rename")
    public ResultNotified<String> renameDirectory(@PathVariable String projectId,
                                                  @PathVariable String directoryId,
                                                  @SessionAttribute UserVo user,
                                                  @RequestBody RenameDirectoryRequest request) {
        ensureProjectAccess(projectId, user);
        Assert.hasText(request.getParentId(), "上层路径不能为空");
        Assert.hasText(request.getName(), "目录名称不能为空");
        usecaseService.updateFolder(directoryId, request.getParentId(), request.getName().trim());
        return new ResultNotified<>(true, "目录保存成功", directoryId);
    }

    @GetMapping("/directories/{directoryId}/delete-preview")
    public ResultNotified<DirectoryDeletePreview> deleteDirectoryPreview(@PathVariable String projectId,
                                                                         @PathVariable String directoryId,
                                                                         @SessionAttribute UserVo user) {
        ensureProjectAccess(projectId, user);
        return buildDirectoryDeletePreviewResult(usecaseService.previewDeleteDirectory(projectId, directoryId));
    }

    @PostMapping("/directories/{directoryId}/delete")
    public ResultNotified<DirectoryDeletePreview> deleteDirectory(@PathVariable String projectId,
                                                                  @PathVariable String directoryId,
                                                                  @SessionAttribute UserVo user,
                                                                  @RequestBody DeleteDirectoryRequest request) {
        ensureProjectAccess(projectId, user);
        Assert.hasText(request.getParentId(), "上层路径不能为空");
        Assert.hasText(request.getName(), "目录名称不能为空");
        return buildDirectoryDeletePreviewResult(usecaseService.deleteDirectory(
                projectId,
                directoryId,
                request.getParentId(),
                request.getName(),
                Boolean.TRUE.equals(request.getDeleteUsecases())
        ));
    }

    private ProjectVo ensureProjectAccess(String projectId, UserVo user) {
        ProjectVo project = projectService.getProjectByProjectIdAndMemberId(projectId, user.getId());
        Assert.notNull(project, "找不到指定项目,或者您没有该项目的访问权限");
        return project;
    }

    private ResultNotified<DirectoryDeletePreview> buildDirectoryDeletePreviewResult(DirectoryDeleteResult serviceResult) {
        DirectoryDeletePreview preview = new DirectoryDeletePreview();
        preview.setDirectoryCount(serviceResult.getDirectoryCount());
        preview.setUsecaseCount(serviceResult.getUsecaseCount());
        preview.setRequiresCascade(serviceResult.isRequiresCascade());
        return new ResultNotified<>(serviceResult.isDeleted() || !serviceResult.isRequiresCascade(), serviceResult.getMessage(), preview);
    }

    private String buildImportErrorMessage(com.oAT.web.service.entity.UsecaseImportResult importResult) {
        if (importResult == null || importResult.getErrors() == null || importResult.getErrors().isEmpty()) {
            return "用例上传失败";
        }
        return importResult.getErrors().stream()
                .limit(10)
                .map(error -> "第" + error.getRowNumber() + "行：" + error.getMessage())
                .collect(Collectors.joining("；"));
    }

    private String[] parseMultiLine(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        return Arrays.stream(text.split("\\r?\\n"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .distinct()
                .toArray(String[]::new);
    }

    private String[] normalizeArray(List<String> values) {
        if (values == null) {
            return null;
        }
        List<String> normalized = values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .collect(Collectors.toList());
        return normalized.isEmpty() ? null : normalized.toArray(new String[0]);
    }

}
