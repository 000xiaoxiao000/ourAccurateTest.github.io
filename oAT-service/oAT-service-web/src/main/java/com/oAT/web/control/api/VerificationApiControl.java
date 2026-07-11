package com.oAT.web.control.api;

import com.alibaba.excel.EasyExcel;
import com.oAT.web.control.entity.ResultNotified;
import com.oAT.web.coverage.universal.IstanbulCoverageParser;
import com.oAT.web.coverage.universal.UniversalCoverageFile;
import com.oAT.web.service.AppService;
import com.oAT.web.service.GitService;
import com.oAT.web.service.ProjectService;
import com.oAT.web.service.entity.AppVo;
import com.oAT.web.service.entity.ProjectVo;
import com.oAT.web.service.entity.UserVo;
import com.oAT.web.verification.VerificationService;
import com.oAT.web.verification.VerificationService.CreateBaseline;
import com.oAT.web.verification.VerificationService.GatePolicy;
import com.oAT.web.verification.VerificationService.ReviewFinding;
import com.oAT.web.verification.VerificationService.ReviewTraceLink;
import com.oAT.web.verification.VerificationService.WriteBackFinding;
import com.oAT.web.verification.model.VerificationModels.*;
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

import java.io.IOException;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@RestController
@RequestMapping("/api/projects/{projectId}/verification")
public class VerificationApiControl {
    private final VerificationService verificationService;
    private final ProjectService projectService;
    private final AppService appService;
    private final GitService gitService;

    public VerificationApiControl(VerificationService verificationService, ProjectService projectService,
                                  AppService appService, GitService gitService) {
        this.verificationService = verificationService;
        this.projectService = projectService;
        this.appService = appService;
        this.gitService = gitService;
    }

    @GetMapping("/overview")
    public ResultNotified<Overview> overview(@PathVariable String projectId, @SessionAttribute UserVo user) {
        ensureProjectAccess(projectId, user);
        return ok("获取AI验证概览成功", new Overview(
                verificationService.assets(projectId, AssetType.REQUIREMENT),
                verificationService.assets(projectId, AssetType.TESTCASE),
                verificationService.assets(projectId, AssetType.SOURCE),
                verificationService.assets(projectId, AssetType.EXECUTION),
                verificationService.assets(projectId, AssetType.COVERAGE),
                verificationService.baselines(projectId)));
    }

    @PostMapping("/assets/import")
    public ResultNotified<AssetSnapshot> importAsset(@PathVariable String projectId,
                                                     @SessionAttribute UserVo user,
                                                     @RequestParam AssetType assetType,
                                                     @RequestParam(defaultValue = "FILE") SourceType sourceType,
                                                     @RequestParam(required = false) MultipartFile file,
                                                     @RequestParam(required = false) String content,
                                                     @RequestParam(required = false) String externalId,
                                                     @RequestParam(required = false) String externalUrl,
                                                     @RequestParam(required = false) String sourceVersion) throws IOException {
        ensureProjectAccess(projectId, user);
        String fileName = file == null ? null : file.getOriginalFilename();
        String importedContent = StringUtils.hasText(content) ? content : readContent(file, assetType);
        Assert.hasText(importedContent, "文件或粘贴内容不能为空");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("automaticSync", sourceType == SourceType.API || sourceType == SourceType.AGENT);
        metadata.put("inputMode", file == null ? "PASTE" : "FILE");
        if (file != null) metadata.put("size", file.getSize());
        AssetSnapshot result = verificationService.importAsset(projectId, user.getId(), assetType,
                file == null && sourceType == SourceType.FILE ? SourceType.PASTE : sourceType, fileName,
                importedContent, externalId, externalUrl, sourceVersion, metadata);
        return ok("资产快照导入成功", result);
    }

    @PostMapping("/assets/git-source")
    public ResultNotified<AssetSnapshot> importGitSource(@PathVariable String projectId,
                                                         @SessionAttribute UserVo user,
                                                         @RequestBody GitSourceImport request) throws IOException {
        ensureProjectAccess(projectId, user);
        GitSourceImport effectiveRequest = request == null
                ? new GitSourceImport(null, null, null, null, null, null, null, null)
                : request;
        GitCredentials credentials = resolveGitCredentials(projectId, effectiveRequest);
        String branch = StringUtils.hasText(effectiveRequest.branch()) ? effectiveRequest.branch().trim() : credentials.branch();
        String commit = StringUtils.hasText(effectiveRequest.commit()) ? effectiveRequest.commit().trim() : credentials.commit();
        Assert.hasText(credentials.repoUrl(), "仓库地址不能为空");
        if (!StringUtils.hasText(commit)) {
            commit = gitService.getLatestCommitId(credentials.repoUrl(), credentials.username(), credentials.password(), branch);
        }
        Assert.hasText(commit, "Commit 不能为空，且无法自动获取最新 Commit");

        File tempZip = Files.createTempFile("oat-verification-source-", ".zip").toFile();
        try {
            gitService.downloadAndPackage(credentials.repoUrl(), credentials.username(), credentials.password(),
                    branch, commit, tempZip);
            SourceSnapshot source = summarizeSourceZip(tempZip, effectiveRequest.maxFiles(), effectiveRequest.maxBytes());
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("automaticSync", false);
            metadata.put("inputMode", "GIT");
            metadata.put("repositoryUrl", credentials.repoUrl());
            metadata.put("branch", branch);
            metadata.put("commit", commit);
            metadata.put("fileCount", source.fileCount());
            metadata.put("truncated", source.truncated());
            metadata.put("appId", effectiveRequest.appId());
            AssetSnapshot asset = verificationService.importAsset(projectId, user.getId(), AssetType.SOURCE,
                    SourceType.GIT, "git-source-" + commit + ".txt", source.content(), effectiveRequest.appId(),
                    credentials.repoUrl(), commit, metadata);
            return ok("Git源码快照导入成功", asset);
        } finally {
            Files.deleteIfExists(tempZip.toPath());
        }
    }

    @PostMapping("/baselines")
    public ResultNotified<Baseline> createBaseline(@PathVariable String projectId, @SessionAttribute UserVo user,
                                                    @RequestBody CreateBaseline request) {
        ensureProjectAccess(projectId, user);
        return ok("分析基线创建成功", verificationService.createBaseline(projectId, user.getId(), request));
    }

    @GetMapping("/baselines/{baselineId}")
    public ResultNotified<BaselineDetail> baseline(@PathVariable String projectId, @PathVariable String baselineId,
                                                   @SessionAttribute UserVo user) {
        ensureProjectAccess(projectId, user);
        return ok("获取分析基线成功", verificationService.detail(projectId, baselineId));
    }

    @PostMapping("/baselines/{baselineId}/analyze")
    public ResultNotified<BaselineDetail> analyze(@PathVariable String projectId, @PathVariable String baselineId,
                                                  @SessionAttribute UserVo user) {
        ensureProjectAccess(projectId, user);
        return ok("AI一致性分析完成", verificationService.analyze(projectId, baselineId));
    }

    @GetMapping("/baselines/{baselineId}/matrix")
    public ResultNotified<List<MatrixRow>> matrix(@PathVariable String projectId, @PathVariable String baselineId,
                                                  @SessionAttribute UserVo user) {
        ensureProjectAccess(projectId, user);
        return ok("获取双向追溯矩阵成功", verificationService.matrix(projectId, baselineId));
    }

    @GetMapping("/baselines/{baselineId}/findings")
    public ResultNotified<List<Finding>> findings(@PathVariable String projectId, @PathVariable String baselineId,
                                                  @SessionAttribute UserVo user) {
        ensureProjectAccess(projectId, user);
        return ok("获取分析问题成功", verificationService.findings(projectId, baselineId));
    }

    @PostMapping("/findings/{findingId}/review")
    public ResultNotified<String> review(@PathVariable String projectId, @PathVariable String findingId,
                                         @SessionAttribute UserVo user, @RequestBody ReviewFinding request) {
        ensureProjectAccess(projectId, user);
        verificationService.reviewFinding(projectId, findingId, user.getId(), request);
        return ok("分析问题审核成功", findingId);
    }

    @PostMapping("/findings/{findingId}/writeback")
    public ResultNotified<WriteBackAction> writeBack(@PathVariable String projectId, @PathVariable String findingId,
                                                     @SessionAttribute UserVo user,
                                                     @RequestBody WriteBackFinding request) {
        ensureProjectAccess(projectId, user);
        return ok("外部回写动作已记录", verificationService.writeBackFinding(projectId, findingId, user.getId(), request));
    }

    @GetMapping("/baselines/{baselineId}/writebacks")
    public ResultNotified<List<WriteBackAction>> writeBacks(@PathVariable String projectId,
                                                            @PathVariable String baselineId,
                                                            @SessionAttribute UserVo user) {
        ensureProjectAccess(projectId, user);
        return ok("获取外部回写动作成功", verificationService.writeBackActions(projectId, baselineId));
    }

    @PostMapping("/trace-links/{traceLinkId}/review")
    public ResultNotified<String> reviewTraceLink(@PathVariable String projectId, @PathVariable String traceLinkId,
                                                  @SessionAttribute UserVo user,
                                                  @RequestBody ReviewTraceLink request) {
        ensureProjectAccess(projectId, user);
        verificationService.reviewTraceLink(projectId, traceLinkId, request);
        return ok("追溯关系审核成功", traceLinkId);
    }

    @PostMapping("/baselines/{baselineId}/quality-gate")
    public ResultNotified<GateResult> qualityGate(@PathVariable String projectId, @PathVariable String baselineId,
                                                  @SessionAttribute UserVo user,
                                                  @RequestBody(required = false) GatePolicy policy) {
        ensureProjectAccess(projectId, user);
        return ok("质量门禁计算完成", verificationService.evaluateGate(projectId, baselineId, policy));
    }

    @PostMapping("/baselines/{baselineId}/stale")
    public ResultNotified<String> markStale(@PathVariable String projectId, @PathVariable String baselineId,
                                            @SessionAttribute UserVo user) {
        ensureProjectAccess(projectId, user);
        verificationService.markBaselineStale(projectId, baselineId);
        return ok("分析基线已标记过期", baselineId);
    }

    private String readContent(MultipartFile file, AssetType assetType) throws IOException {
        if (file == null || file.isEmpty()) return "";
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if ((name.endsWith(".zip") || name.endsWith(".jar")) && assetType == AssetType.SOURCE) {
            File tempZip = Files.createTempFile("oat-verification-upload-source-", ".zip").toFile();
            try {
                file.transferTo(tempZip);
                return summarizeSourceZip(tempZip, 120, 300_000).content();
            } finally {
                Files.deleteIfExists(tempZip.toPath());
            }
        }
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
            List<Map<Integer, String>> rows = EasyExcel.read(file.getInputStream()).headRowNumber(0)
                    .doReadAllSync();
            return rows.stream().map(row -> row.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .map(entry -> value(entry.getValue())).collect(Collectors.joining("\t")))
                    .collect(Collectors.joining("\n"));
        }
        if (assetType == AssetType.COVERAGE && name.endsWith(".json")) {
            return summarizeIstanbulCoverage(file.getBytes());
        }
        if (name.endsWith(".pdf") || name.endsWith(".doc") || name.endsWith(".docx"))
            throw new IllegalArgumentException("当前MVP暂不解析PDF/Word，请先导出为Markdown、文本、CSV或Excel");
        return new String(file.getBytes(), StandardCharsets.UTF_8);
    }

    private ProjectVo ensureProjectAccess(String projectId, UserVo user) {
        ProjectVo project = projectService.getProjectByProjectIdAndMemberId(projectId, user.getId());
        Assert.notNull(project, "找不到指定项目,或者您没有该项目的访问权限");
        return project;
    }

    private <T> ResultNotified<T> ok(String message, T data) {
        return new ResultNotified<>(true, message, data);
    }

    private String value(Object value) { return value == null ? "" : String.valueOf(value).replace('\t', ' '); }

    public record Overview(List<AssetSnapshot> requirements, List<AssetSnapshot> testcases,
                           List<AssetSnapshot> sources, List<AssetSnapshot> executions,
                           List<AssetSnapshot> coverages, List<Baseline> baselines) {}

    public record GitSourceImport(String appId, String repositoryUrl, String username, String password,
                                  String branch, String commit, Integer maxFiles, Integer maxBytes) {}
    private record GitCredentials(String repoUrl, String username, String password, String branch, String commit) {}
    private record SourceSnapshot(String content, int fileCount, boolean truncated) {}

    private GitCredentials resolveGitCredentials(String projectId, GitSourceImport request) {
        if (request != null && StringUtils.hasText(request.appId())) {
            AppVo app = appService.getApp(request.appId());
            Assert.notNull(app, "找不到指定应用");
            Assert.isTrue(projectId.equals(app.getCreateProjectId()), "应用不属于当前项目");
            return new GitCredentials(
                    StringUtils.hasText(request.repositoryUrl()) ? request.repositoryUrl() : app.getRepoAddress(),
                    StringUtils.hasText(request.username()) ? request.username() : app.getRepoUserName(),
                    StringUtils.hasText(request.password()) ? request.password() : app.getRepoPassword(),
                    StringUtils.hasText(request.branch()) ? request.branch() : app.getCurrentBranch(),
                    StringUtils.hasText(request.commit()) ? request.commit() : app.getCurrentCommitId());
        }
        return new GitCredentials(request == null ? null : request.repositoryUrl(),
                request == null ? null : request.username(), request == null ? null : request.password(),
                request == null ? null : request.branch(), request == null ? null : request.commit());
    }

    private SourceSnapshot summarizeSourceZip(File zipFile, Integer maxFiles, Integer maxBytes) throws IOException {
        int fileLimit = maxFiles == null || maxFiles <= 0 ? 120 : Math.min(maxFiles, 500);
        int byteLimit = maxBytes == null || maxBytes <= 0 ? 300_000 : Math.min(maxBytes, 2_000_000);
        StringBuilder builder = new StringBuilder();
        int count = 0;
        boolean truncated = false;
        try (ZipFile zip = new ZipFile(zipFile, StandardCharsets.UTF_8)) {
            List<? extends ZipEntry> entries = zip.stream()
                    .filter(entry -> !entry.isDirectory())
                    .filter(entry -> entry.getName().endsWith(".java"))
                    .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                    .toList();
            for (ZipEntry entry : entries) {
                if (count >= fileLimit || builder.length() >= byteLimit) {
                    truncated = true;
                    break;
                }
                String content = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
                int remaining = byteLimit - builder.length();
                if (remaining <= 0) {
                    truncated = true;
                    break;
                }
                if (content.length() > remaining) {
                    content = content.substring(0, remaining);
                    truncated = true;
                }
                builder.append("\n\n// FILE: ").append(entry.getName()).append('\n').append(content);
                count++;
            }
        }
        Assert.isTrue(count > 0, "Git源码包中未找到 Java 源文件");
        builder.append("\n\n// SNAPSHOT_ID: ").append(UUID.randomUUID());
        return new SourceSnapshot(builder.toString(), count, truncated);
    }

    private String summarizeIstanbulCoverage(byte[] payload) {
        List<UniversalCoverageFile> files = new IstanbulCoverageParser().parse(payload);
        StringBuilder builder = new StringBuilder("Istanbul coverage summary\n");
        for (UniversalCoverageFile file : files.stream().limit(300).toList()) {
            long coveredLines = file.getLines().stream().filter(line -> line.getCoveredCount() > 0).count();
            long coveredBranches = file.getBranches().stream().filter(branch -> branch.getCoveredCount() > 0).count();
            builder.append(file.getFilePath())
                    .append(" lines ").append(coveredLines).append('/').append(file.getLines().size())
                    .append(" branches ").append(coveredBranches).append('/').append(file.getBranches().size())
                    .append('\n');
        }
        return builder.toString();
    }
}
