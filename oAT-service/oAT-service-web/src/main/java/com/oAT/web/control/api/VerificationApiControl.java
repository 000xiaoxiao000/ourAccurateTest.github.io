package com.oAT.web.control.api;

import com.alibaba.excel.EasyExcel;
import com.oAT.web.control.entity.ResultNotified;
import com.oAT.web.coverage.universal.JacocoExecToXmlConverter;
import com.oAT.web.service.AppService;
import com.oAT.web.service.GitService;
import com.oAT.web.service.ProjectService;
import com.oAT.web.service.entity.AppVo;
import com.oAT.web.service.entity.ProjectVo;
import com.oAT.web.service.entity.UserVo;
import com.oAT.web.verification.VerificationService;
import com.oAT.web.verification.VerificationService.CreateBaseline;
import com.oAT.web.verification.VerificationService.ReviewFinding;
import com.oAT.web.verification.VerificationService.ReviewTraceLink;
import com.oAT.web.verification.VerificationService.UpdateAsset;
import com.oAT.web.verification.VerificationService.UpdateBaseline;
import com.oAT.web.verification.VerificationService.WriteBackFinding;
import com.oAT.web.verification.SourceAssetFilter;
import com.oAT.web.verification.SourceAssetFilter.SourceProfile;
import com.oAT.web.verification.connector.ConnectorRegistry;
import com.oAT.web.verification.model.VerificationModels.*;
import com.oAT.web.verification.qualitygate.QualityGateService;
import com.oAT.web.verification.traceability.ChangeImpactService;
import com.oAT.web.verification.impact.GitImpactAnalysisService;
import com.oAT.web.verification.impact.ImpactTraceabilityMapper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.File;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@RestController
@RequestMapping("/api/projects/{projectId}/verification")
public class VerificationApiControl {
    private static final Set<String> SOURCE_EXTENSIONS = Set.of(
            ".java", ".kt", ".kts", ".scala", ".groovy",
            ".js", ".jsx", ".ts", ".tsx", ".vue",
            ".py", ".go", ".rs", ".c", ".cc", ".cpp", ".h", ".hpp",
            ".cs", ".php", ".rb", ".swift", ".m", ".mm",
            ".sql", ".xml", ".yaml", ".yml", ".json", ".properties");
    private static final Set<String> COVERAGE_EXTENSIONS = Set.of(
            ".exec", ".xml", ".html", ".json", ".info", ".lcov", ".txt", ".out", ".cov", ".coverage", ".csv", ".tsv");
    private static final Set<String> X_MIND_TEXT_ENTRIES = Set.of(
            "content.json", "content.xml", "metadata.json", "manifest.json");
    private static final String SOURCE_TREE_BEGIN = "// SOURCE_TREE_BEGIN";
    private static final String SOURCE_TREE_END = "// SOURCE_TREE_END";
    private static final String SOURCE_FILE_PREFIX = "// SOURCE_FILE: ";
    private final ConcurrentHashMap<String, GitImpactAnalysisJob> gitImpactJobs = new ConcurrentHashMap<>();

    private final VerificationService verificationService;
    private final ProjectService projectService;
    private final AppService appService;
    private final GitService gitService;
    private final QualityGateService qualityGateService;
    private final ChangeImpactService changeImpactService;
    private final GitImpactAnalysisService gitImpactAnalysisService;
    private final ImpactTraceabilityMapper impactTraceabilityMapper;
    private final ConnectorRegistry connectorRegistry;
    private final JacocoExecToXmlConverter jacocoExecToXmlConverter;
    private final Executor verificationAiExecutor;

    public VerificationApiControl(VerificationService verificationService, ProjectService projectService,
                                  AppService appService, GitService gitService,
                                  QualityGateService qualityGateService,
                                  ChangeImpactService changeImpactService,
                                  GitImpactAnalysisService gitImpactAnalysisService,
                                  ImpactTraceabilityMapper impactTraceabilityMapper,
                                  ConnectorRegistry connectorRegistry,
                                  JacocoExecToXmlConverter jacocoExecToXmlConverter,
                                  @Qualifier("verificationAiExecutor") Executor verificationAiExecutor) {
        this.verificationService = verificationService;
        this.projectService = projectService;
        this.appService = appService;
        this.gitService = gitService;
        this.qualityGateService = qualityGateService;
        this.changeImpactService = changeImpactService;
        this.gitImpactAnalysisService = gitImpactAnalysisService;
        this.impactTraceabilityMapper = impactTraceabilityMapper;
        this.connectorRegistry = connectorRegistry;
        this.jacocoExecToXmlConverter = jacocoExecToXmlConverter;
        this.verificationAiExecutor = verificationAiExecutor;
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
                verificationService.assets(projectId, AssetType.DEFECT),
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
                                                     @RequestParam(required = false) String sourceVersion,
                                                     @RequestParam(required = false) String appId) throws IOException {
        ensureProjectAccess(projectId, user);
        String fileName = file == null ? null : file.getOriginalFilename();
        String importedContent = StringUtils.hasText(content) ? content : readContent(file, assetType);
        Assert.hasText(importedContent, "文件或粘贴内容不能为空");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("automaticSync", sourceType == SourceType.API || sourceType == SourceType.AGENT);
        metadata.put("inputMode", file == null ? "PASTE" : "FILE");
        if (file != null) metadata.put("size", file.getSize());
        if (StringUtils.hasText(appId)) metadata.put("appId", appId.trim());
        AssetSnapshot result = verificationService.importAsset(projectId, user.getId(), assetType,
                file == null && sourceType == SourceType.FILE ? SourceType.PASTE : sourceType, fileName,
                importedContent, externalId, externalUrl, sourceVersion, metadata);
        return ok("资产快照导入成功", result);
    }

    @PutMapping("/assets/{assetId}")
    public ResultNotified<AssetSnapshot> updateAsset(@PathVariable String projectId,
                                                     @PathVariable String assetId,
                                                     @SessionAttribute UserVo user,
                                                     @RequestBody UpdateAsset request) {
        ensureProjectAccess(projectId, user);
        return ok("资料更新成功", verificationService.updateAsset(projectId, assetId, user.getId(), request));
    }

    @DeleteMapping("/assets/{assetId}")
    public ResultNotified<String> deleteAsset(@PathVariable String projectId,
                                              @PathVariable String assetId,
                                              @SessionAttribute UserVo user) {
        ensureProjectAccess(projectId, user);
        verificationService.deleteAsset(projectId, assetId);
        return ok("资料删除成功", assetId);
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
        String branch = optionalText(StringUtils.hasText(effectiveRequest.branch()) ? effectiveRequest.branch() : credentials.branch());
        String commit = StringUtils.hasText(effectiveRequest.commit()) ? effectiveRequest.commit().trim() : credentials.commit();
        Assert.hasText(credentials.repoUrl(), "仓库地址不能为空");
        AppVo sourceApp = resolveSourceApp(projectId, effectiveRequest.appId());
        if (!StringUtils.hasText(commit)) {
            try {
                commit = gitService.getLatestCommitId(credentials.repoUrl(), credentials.username(), credentials.password(), branch);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(e.getMessage() == null ? "无法获取 Git 最新 Commit" : e.getMessage(), e);
            }
        }
        Assert.hasText(commit, "Commit 不能为空，且无法自动获取最新 Commit");

        File tempZip = Files.createTempFile("oat-verification-source-", ".zip").toFile();
        try {
            try {
                gitService.downloadAndPackage(credentials.repoUrl(), credentials.username(), credentials.password(),
                        branch, commit, tempZip);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(e.getMessage() == null ? "Git 源码拉取失败" : e.getMessage(), e);
            }
            SourceSnapshot source = summarizeSourceZip(tempZip, effectiveRequest.maxFiles(), effectiveRequest.maxBytes(),
                    SourceAssetFilter.fromApp(sourceApp));
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("automaticSync", false);
            metadata.put("inputMode", "GIT");
            metadata.put("repositoryUrl", credentials.repoUrl());
            metadata.put("branch", branch);
            metadata.put("commit", commit);
            metadata.put("fileCount", source.totalFileCount());
            metadata.put("totalFileCount", source.totalFileCount());
            metadata.put("sampledFileCount", source.sampledFileCount());
            metadata.put("truncated", source.truncated());
            metadata.put("appId", effectiveRequest.appId());
            metadata.putAll(SourceAssetFilter.metadataForApp(sourceApp));
            AssetSnapshot asset = verificationService.importAsset(projectId, user.getId(), AssetType.SOURCE,
                    SourceType.GIT, "git-source-" + commit + ".txt", source.content(), effectiveRequest.appId(),
                    credentials.repoUrl(), commit, metadata);
            return ok("Git源码快照导入成功", asset);
        } finally {
            Files.deleteIfExists(tempZip.toPath());
        }
    }

    private String optionalText(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    @PostMapping("/baselines")
    public ResultNotified<Baseline> createBaseline(@PathVariable String projectId, @SessionAttribute UserVo user,
                                                    @RequestBody CreateBaseline request) {
        ensureProjectAccess(projectId, user);
        return ok("分析基线创建成功", verificationService.createBaseline(projectId, user.getId(), request));
    }

    @PutMapping("/baselines/{baselineId}")
    public ResultNotified<Baseline> updateBaseline(@PathVariable String projectId,
                                                   @PathVariable String baselineId,
                                                   @SessionAttribute UserVo user,
                                                   @RequestBody UpdateBaseline request) {
        ensureProjectAccess(projectId, user);
        return ok("分析基线更新成功", verificationService.updateBaseline(projectId, baselineId, request));
    }

    @DeleteMapping("/baselines/{baselineId}")
    public ResultNotified<String> deleteBaseline(@PathVariable String projectId,
                                                 @PathVariable String baselineId,
                                                 @SessionAttribute UserVo user) {
        ensureProjectAccess(projectId, user);
        verificationService.deleteBaseline(projectId, baselineId);
        return ok("分析基线删除成功", baselineId);
    }

    @GetMapping("/baselines/{baselineId}")
    public ResultNotified<BaselineDetail> baseline(@PathVariable String projectId, @PathVariable String baselineId,
                                                   @SessionAttribute UserVo user) {
        ensureProjectAccess(projectId, user);
        return ok("获取分析基线成功", verificationService.detail(projectId, baselineId));
    }

    @PostMapping("/baselines/{baselineId}/analyze")
    public ResultNotified<AnalysisJob> analyze(@PathVariable String projectId, @PathVariable String baselineId,
                                               @SessionAttribute UserVo user) {
        ensureProjectAccess(projectId, user);
        return ok("AI一致性分析任务已创建", verificationService.startAnalysis(projectId, baselineId, user.getId()));
    }

    @GetMapping("/analysis-jobs/{jobId}")
    public ResultNotified<AnalysisJob> analysisJob(@PathVariable String projectId, @PathVariable String jobId,
                                                   @SessionAttribute UserVo user) {
        ensureProjectAccess(projectId, user);
        return ok("获取AI分析任务成功", verificationService.analysisJob(projectId, jobId));
    }

    @GetMapping("/baselines/{baselineId}/analysis-jobs/latest")
    public ResultNotified<AnalysisJob> latestAnalysisJob(@PathVariable String projectId,
                                                         @PathVariable String baselineId,
                                                         @SessionAttribute UserVo user) {
        ensureProjectAccess(projectId, user);
        return ok("获取最近AI分析任务成功", verificationService.latestAnalysisJob(projectId, baselineId));
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
        return ok("AI回写内容已生成并记录", verificationService.writeBackFinding(projectId, findingId, user.getId(), request));
    }

    @GetMapping("/baselines/{baselineId}/writebacks")
    public ResultNotified<List<WriteBackAction>> writeBacks(@PathVariable String projectId,
                                                            @PathVariable String baselineId,
                                                            @SessionAttribute UserVo user) {
        ensureProjectAccess(projectId, user);
        return ok("获取AI回写记录成功", verificationService.writeBackActions(projectId, baselineId));
    }

    @PostMapping("/trace-links/{traceLinkId}/review")
    public ResultNotified<String> reviewTraceLink(@PathVariable String projectId, @PathVariable String traceLinkId,
                                                  @SessionAttribute UserVo user,
                                                  @RequestBody ReviewTraceLink request) {
        ensureProjectAccess(projectId, user);
        verificationService.reviewTraceLink(projectId, traceLinkId, request);
        return ok("追溯关系审核成功", traceLinkId);
    }

    @PostMapping("/baselines/{baselineId}/stale")
    public ResultNotified<String> markStale(@PathVariable String projectId, @PathVariable String baselineId,
                                            @SessionAttribute UserVo user) {
        ensureProjectAccess(projectId, user);
        verificationService.markBaselineStale(projectId, baselineId);
        return ok("分析基线已标记过期", baselineId);
    }

    // ── Quality Gate ──────────────────────────────────────────────────────────

    @PostMapping("/quality-gate/policies")
    public ResultNotified<QualityGatePolicy> createPolicy(@PathVariable String projectId,
                                                          @SessionAttribute UserVo user,
                                                          @RequestBody QualityGatePolicy request) {
        ensureProjectAccess(projectId, user);
        return ok("质量门禁策略已创建", qualityGateService.createPolicy(projectId, user.getId(), request));
    }

    @GetMapping("/quality-gate/policies")
    public ResultNotified<List<QualityGatePolicy>> listPolicies(@PathVariable String projectId,
                                                                @SessionAttribute UserVo user) {
        ensureProjectAccess(projectId, user);
        return ok("获取质量门禁策略列表成功", qualityGateService.listPolicies(projectId));
    }

    @PostMapping("/baselines/{baselineId}/quality-gate/evaluate")
    public ResultNotified<QualityGateResult> evaluateGate(@PathVariable String projectId,
                                                          @PathVariable String baselineId,
                                                          @SessionAttribute UserVo user,
                                                          @RequestBody EvaluateGateRequest request) {
        ensureProjectAccess(projectId, user);
        Assert.hasText(request.policyId(), "policyId 不能为空");
        return ok("质量门禁评估完成",
                qualityGateService.evaluate(projectId, baselineId, request.policyId(), user.getId()));
    }

    @GetMapping("/baselines/{baselineId}/quality-gate/results")
    public ResultNotified<List<QualityGateResult>> gateResults(@PathVariable String projectId,
                                                               @PathVariable String baselineId,
                                                               @SessionAttribute UserVo user) {
        ensureProjectAccess(projectId, user);
        return ok("获取质量门禁结果成功", qualityGateService.listResults(projectId, baselineId));
    }

    @PostMapping("/baselines/{baselineId}/quality-gate/exemptions")
    public ResultNotified<GateExemption> createExemption(@PathVariable String projectId,
                                                         @PathVariable String baselineId,
                                                         @SessionAttribute UserVo user,
                                                         @RequestBody ExemptionRequest request) {
        ensureProjectAccess(projectId, user);
        return ok("质量门禁豁免已创建",
                qualityGateService.createExemption(projectId, baselineId, request.ruleId(),
                        request.reason(), user.getId(), request.expiresAt()));
    }

    @GetMapping("/baselines/{baselineId}/quality-gate/exemptions")
    public ResultNotified<List<GateExemption>> listExemptions(@PathVariable String projectId,
                                                              @PathVariable String baselineId,
                                                              @SessionAttribute UserVo user) {
        ensureProjectAccess(projectId, user);
        return ok("获取豁免列表成功", qualityGateService.listExemptions(projectId, baselineId));
    }

    // ── Change Impact ─────────────────────────────────────────────────────────

    @PostMapping("/baselines/{baselineId}/change-impact")
    public ResultNotified<ChangeImpactReport> analyzeImpact(@PathVariable String projectId,
                                                            @PathVariable String baselineId,
                                                            @SessionAttribute UserVo user,
                                                            @RequestBody(required = false) ChangeImpactRequest request) {
        ensureProjectAccess(projectId, user);
        String desc = request != null ? request.changeDescription() : null;
        return ok("变更影响分析完成",
                changeImpactService.analyzeImpact(projectId, baselineId, desc, user.getId()));
    }

    @PostMapping("/baselines/{baselineId}/git-change-impact")
    public ResultNotified<GitChangeImpactResponse> analyzeGitImpact(@PathVariable String projectId,
                                                                     @PathVariable String baselineId,
                                                                     @SessionAttribute UserVo user,
                                                                     @RequestBody GitChangeImpactRequest request) {
        ensureProjectAccess(projectId, user);
        Assert.notNull(request, "请求不能为空");
        Assert.hasText(request.appId(), "appId 不能为空");
        Assert.hasText(request.baseCommit(), "baseCommit 不能为空");
        Assert.hasText(request.headCommit(), "headCommit 不能为空");
        AppVo app = resolveSourceApp(projectId, request.appId());
        var report = gitImpactAnalysisService.analyze(app, request.baseCommit(), request.headCommit());
        var traceability = impactTraceabilityMapper.map(baselineId, report.candidates());
        return ok("Git 变更影响分析完成", new GitChangeImpactResponse(report, traceability));
    }

    @PostMapping("/baselines/{baselineId}/git-change-impact-jobs")
    public ResultNotified<GitImpactAnalysisJob> startGitImpactJob(@PathVariable String projectId,
                                                                  @PathVariable String baselineId,
                                                                  @SessionAttribute UserVo user,
                                                                  @RequestBody GitChangeImpactRequest request) {
        ensureProjectAccess(projectId, user);
        Assert.notNull(request, "请求不能为空");
        Assert.hasText(request.appId(), "appId 不能为空");
        Assert.hasText(request.baseCommit(), "baseCommit 不能为空");
        Assert.hasText(request.headCommit(), "headCommit 不能为空");
        AppVo app = resolveSourceApp(projectId, request.appId());
        String jobId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        updateGitImpactJob(new GitImpactAnalysisJob(jobId, projectId, baselineId, GitImpactJobStatus.PENDING,
                "PENDING", 1, "分析任务已创建", null, null, now, now));
        CompletableFuture.runAsync(() -> runGitImpactJob(jobId, projectId, baselineId, app, request), verificationAiExecutor);
        return ok("Git 变更影响分析任务已创建", gitImpactJobs.get(jobId));
    }

    @GetMapping("/git-change-impact-jobs/{jobId}")
    public ResultNotified<GitImpactAnalysisJob> getGitImpactJob(@PathVariable String projectId,
                                                                @PathVariable String jobId,
                                                                @SessionAttribute UserVo user) {
        ensureProjectAccess(projectId, user);
        GitImpactAnalysisJob job = gitImpactJobs.get(jobId);
        Assert.notNull(job, "Git 影响分析任务不存在或已过期");
        Assert.isTrue(projectId.equals(job.projectId()), "无权访问该分析任务");
        return ok("获取 Git 影响分析进度成功", job);
    }

    @GetMapping("/git-change-impact/{reportId}/llm-review")
    public ResultNotified<GitImpactAnalysisService.LlmReviewProgress> getGitImpactLlmReview(@PathVariable String projectId,
                                                                                            @PathVariable String reportId,
                                                                                            @SessionAttribute UserVo user) {
        ensureProjectAccess(projectId, user);
        return ok("获取 Git 影响 LLM 审阅进度成功", gitImpactAnalysisService.llmReview(reportId));
    }

    private void runGitImpactJob(String jobId, String projectId, String baselineId, AppVo app, GitChangeImpactRequest request) {
        try {
            updateGitImpactJob(progressJob(jobId, GitImpactJobStatus.RUNNING, "STARTING", 3, "正在启动 Git 影响分析", null, null));
            var report = gitImpactAnalysisService.analyze(app, request.baseCommit(), request.headCommit(), progress ->
                    updateGitImpactJob(progressJob(jobId, GitImpactJobStatus.RUNNING, progress.stage(), progress.percent(), progress.message(), null, null)));
            updateGitImpactJob(progressJob(jobId, GitImpactJobStatus.RUNNING, "MAPPING_TRACEABILITY", 96, "正在映射验收标准和回归用例", null, null));
            var traceability = impactTraceabilityMapper.map(baselineId, report.candidates());
            updateGitImpactJob(progressJob(jobId, GitImpactJobStatus.COMPLETED, "COMPLETED", 100, "Git 影响分析完成", new GitChangeImpactResponse(report, traceability), null));
        } catch (RuntimeException exception) {
            updateGitImpactJob(progressJob(jobId, GitImpactJobStatus.FAILED, "FAILED", 100, "Git 影响分析失败", null, exception.getMessage()));
        }
    }

    private GitImpactAnalysisJob progressJob(String jobId, GitImpactJobStatus status, String stage, int percent, String message,
                                             GitChangeImpactResponse result, String error) {
        GitImpactAnalysisJob existing = gitImpactJobs.get(jobId);
        LocalDateTime createdAt = existing == null ? LocalDateTime.now() : existing.createdAt();
        return new GitImpactAnalysisJob(jobId,
                existing == null ? "" : existing.projectId(),
                existing == null ? "" : existing.baselineId(),
                status, stage, Math.max(0, Math.min(100, percent)), message,
                result == null && existing != null ? existing.result() : result,
                error, createdAt, LocalDateTime.now());
    }

    private void updateGitImpactJob(GitImpactAnalysisJob job) {
        gitImpactJobs.put(job.jobId(), job);
    }

    // ── Connector types ───────────────────────────────────────────────────────

    @GetMapping("/connectors/types")
    public ResultNotified<List<String>> connectorTypes(@PathVariable String projectId,
                                                       @SessionAttribute UserVo user) {
        ensureProjectAccess(projectId, user);
        return ok("获取连接器类型列表成功", connectorRegistry.availableTypes());
    }

    // ── Request records ───────────────────────────────────────────────────────

    public record EvaluateGateRequest(String policyId) {}
    public record ExemptionRequest(String ruleId, String reason, LocalDateTime expiresAt) {}
    public record ChangeImpactRequest(String changeDescription) {}
    public record GitChangeImpactRequest(String appId, String baseCommit, String headCommit) {}
    public record GitChangeImpactResponse(com.oAT.web.verification.impact.ImpactModels.ImpactReport report,
                                          ImpactTraceabilityMapper.TraceabilityImpact traceability) {}
    public enum GitImpactJobStatus { PENDING, RUNNING, COMPLETED, FAILED }
    public record GitImpactAnalysisJob(String jobId, String projectId, String baselineId, GitImpactJobStatus status,
                                       String stage, int percent, String message, GitChangeImpactResponse result,
                                       String error, LocalDateTime createdAt, LocalDateTime updatedAt) {}

    private String readContent(MultipartFile file, AssetType assetType) throws IOException {
        if (file == null || file.isEmpty()) return "";
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if ((name.endsWith(".zip") || name.endsWith(".jar")) && assetType == AssetType.SOURCE) {
            File tempZip = Files.createTempFile("oat-verification-upload-source-", ".zip").toFile();
            try {
                file.transferTo(tempZip);
                return summarizeSourceZip(tempZip, 120, 300_000, SourceProfile.any()).content();
            } finally {
                Files.deleteIfExists(tempZip.toPath());
            }
        }
        if (name.endsWith(".zip") && assetType == AssetType.COVERAGE) {
            File tempZip = Files.createTempFile("oat-verification-upload-coverage-", ".zip").toFile();
            try {
                file.transferTo(tempZip);
                return summarizeCoverageArchive(tempZip);
            } finally {
                Files.deleteIfExists(tempZip.toPath());
            }
        }
        if (name.endsWith(".exec") && assetType == AssetType.COVERAGE) {
            throw new IllegalArgumentException("JaCoCo exec 需要与 classfiles 一起上传，请打包为 zip，包含 jacoco.exec 和 classes/ 或 target/classes/");
        }
        if (name.endsWith(".xmind")) {
            File tempXmind = Files.createTempFile("oat-verification-upload-mindmap-", ".xmind").toFile();
            try {
                file.transferTo(tempXmind);
                return extractXmind(tempXmind);
            } finally {
                Files.deleteIfExists(tempXmind.toPath());
            }
        }
        if (name.endsWith(".docx")) return extractDocx(file.getBytes());
        if (name.endsWith(".doc")) return extractDoc(file.getBytes());
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
            List<Map<Integer, String>> rows = EasyExcel.read(file.getInputStream()).headRowNumber(0)
                    .doReadAllSync();
            return rows.stream().map(row -> row.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .map(entry -> value(entry.getValue())).collect(Collectors.joining("\t")))
                    .collect(Collectors.joining("\n"));
        }
        if (name.endsWith(".mm") || name.endsWith(".opml")) {
            return "脑图/大纲资料: " + file.getOriginalFilename() + "\n" + new String(file.getBytes(), StandardCharsets.UTF_8);
        }
        if (name.endsWith(".pdf"))
            throw new IllegalArgumentException("当前暂不解析PDF，请先导出为 Word、Markdown、文本、CSV 或 Excel");
        return new String(file.getBytes(), StandardCharsets.UTF_8);
    }

    private String extractDocx(byte[] bytes) throws IOException {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder builder = new StringBuilder("Word文档内容\n");
            document.getParagraphs().forEach(paragraph -> {
                String text = paragraph.getText();
                if (StringUtils.hasText(text)) builder.append(text.trim()).append('\n');
            });
            document.getTables().forEach(table -> {
                table.getRows().forEach(row -> builder.append(row.getTableCells().stream()
                        .map(cell -> value(cell.getText())).collect(Collectors.joining("\t"))).append('\n'));
            });
            return builder.toString();
        }
    }

    private String extractDoc(byte[] bytes) throws IOException {
        try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(bytes));
             WordExtractor extractor = new WordExtractor(document)) {
            return "Word文档内容\n" + value(extractor.getText());
        }
    }

    private String extractXmind(File xmindFile) throws IOException {
        StringBuilder builder = new StringBuilder("XMind脑图内容\n");
        int count = 0;
        try (ZipFile zip = new ZipFile(xmindFile, StandardCharsets.UTF_8)) {
            List<? extends ZipEntry> entries = zip.stream()
                    .filter(entry -> !entry.isDirectory())
                    .filter(entry -> X_MIND_TEXT_ENTRIES.contains(entry.getName())
                            || entry.getName().endsWith("/content.json")
                            || entry.getName().endsWith("/content.xml"))
                    .sorted(Comparator.comparing(ZipEntry::getName))
                    .toList();
            for (ZipEntry entry : entries) {
                builder.append("\n\n// MINDMAP_ENTRY: ").append(entry.getName()).append('\n')
                        .append(new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8));
                count++;
            }
        }
        Assert.isTrue(count > 0, "XMind文件中未找到可读取的脑图内容");
        return builder.toString();
    }

    private String summarizeCoverageArchive(File archiveFile) throws IOException {
        String jacocoXml = convertJacocoExecArchiveIfPresent(archiveFile);
        if (jacocoXml != null) return jacocoXml;

        StringBuilder builder = new StringBuilder("多语言覆盖率资料\n");
        int count = 0;
        try (ZipFile zip = new ZipFile(archiveFile, StandardCharsets.UTF_8)) {
            List<? extends ZipEntry> entries = zip.stream()
                    .filter(entry -> !entry.isDirectory())
                    .filter(entry -> COVERAGE_EXTENSIONS.stream().anyMatch(ext -> entry.getName().toLowerCase().endsWith(ext)))
                    .sorted(Comparator.comparing(ZipEntry::getName))
                    .limit(200)
                    .toList();
            for (ZipEntry entry : entries) {
                String content = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
                builder.append("\n\n// COVERAGE_FILE: ").append(entry.getName()).append('\n')
                        .append(content, 0, Math.min(content.length(), 20_000));
                count++;
            }
        }
        Assert.isTrue(count > 0, "覆盖率压缩包中未找到可读取的覆盖率文件");
        return builder.toString();
    }

    private String convertJacocoExecArchiveIfPresent(File archiveFile) throws IOException {
        try (ZipFile zip = new ZipFile(archiveFile, StandardCharsets.UTF_8)) {
            boolean hasExec = zip.stream().anyMatch(entry -> !entry.isDirectory()
                    && entry.getName().toLowerCase().endsWith(".exec"));
            if (!hasExec) return null;
        }

        Path tempDir = Files.createTempDirectory("oat-jacoco-exec-");
        try {
            unzipSafely(archiveFile, tempDir);
            List<File> execFiles = listFiles(tempDir, path -> path.getFileName().toString().toLowerCase().endsWith(".exec"))
                    .stream()
                    .map(Path::toFile)
                    .toList();
            Assert.isTrue(execFiles.size() == 1, "覆盖率压缩包中必须包含且仅包含一个 JaCoCo exec 文件");

            List<File> classRoots = discoverClassRoots(tempDir);
            Assert.notEmpty(classRoots, "JaCoCo exec 覆盖率包中未找到业务 class 文件，请包含 classes/、target/classes/、应用 jar 或 war");
            List<File> sourceRoots = discoverSourceRoots(tempDir);
            if (sourceRoots.isEmpty()) sourceRoots = List.of(tempDir.toFile());
            String xml = jacocoExecToXmlConverter.convert(execFiles.get(0), classRoots, sourceRoots);
            Assert.isTrue(xml.contains("<sourcefile ") && xml.contains("<line "),
                    "JaCoCo exec 已读取，但未匹配到可展示的行覆盖率。请确认 zip 中的 exec 来自同一个目标 jar/war，且目标包保留了行号调试信息。");
            return xml;
        } finally {
            deleteIteratively(tempDir);
        }
    }

    private void unzipSafely(File archiveFile, Path targetDir) throws IOException {
        try (ZipFile zip = new ZipFile(archiveFile, StandardCharsets.UTF_8)) {
            List<? extends ZipEntry> entries = zip.stream()
                    .filter(entry -> !entry.isDirectory())
                    .filter(entry -> !isMacOsMetadataEntry(entry.getName()))
                    .sorted(Comparator.comparing(ZipEntry::getName))
                    .toList();
            for (ZipEntry entry : entries) {
                Path target = targetDir.resolve(entry.getName()).normalize();
                if (!target.startsWith(targetDir)) throw new IllegalArgumentException("覆盖率压缩包包含非法路径: " + entry.getName());
                Files.createDirectories(target.getParent());
                Files.copy(zip.getInputStream(entry), target);
            }
        }
    }

    private List<File> discoverClassRoots(Path root) throws IOException {
        Set<Path> roots = new java.util.LinkedHashSet<>();
        for (Path path : listFiles(root, path -> {
            String name = path.getFileName().toString().toLowerCase();
            return !isMacOsMetadataEntry(root.relativize(path).toString())
                    && (name.endsWith(".class") || (isJavaArchive(path) && !isDependencyArchive(root, path)));
        })) {
            roots.add(classRootFor(root, path));
        }
        return roots.stream().map(Path::toFile).toList();
    }

    private boolean isMacOsMetadataEntry(String name) {
        String normalized = name == null ? "" : name.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String fileName = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return normalized.startsWith("__MACOSX/") || fileName.startsWith("._") || ".DS_Store".equals(fileName);
    }

    private Path classRootFor(Path archiveRoot, Path classFile) {
        Path relative = archiveRoot.relativize(classFile);
        int nameCount = relative.getNameCount();
        for (int i = 0; i < nameCount; i++) {
            String segment = relative.getName(i).toString();
            if ("classes".equals(segment)) return archiveRoot.resolve(relative.subpath(0, i + 1));
            if ("BOOT-INF".equals(segment) && i + 1 < nameCount && "classes".equals(relative.getName(i + 1).toString())) {
                return archiveRoot.resolve(relative.subpath(0, i + 2));
            }
            if ("WEB-INF".equals(segment) && i + 1 < nameCount && "classes".equals(relative.getName(i + 1).toString())) {
                return archiveRoot.resolve(relative.subpath(0, i + 2));
            }
        }
        if (isJavaArchive(classFile)) return classFile;
        return archiveRoot;
    }

    private boolean isDependencyArchive(Path archiveRoot, Path archiveFile) {
        Path relative = archiveRoot.relativize(archiveFile);
        for (int i = 0; i + 1 < relative.getNameCount(); i++) {
            String segment = relative.getName(i).toString();
            if (("BOOT-INF".equals(segment) || "WEB-INF".equals(segment))
                    && "lib".equals(relative.getName(i + 1).toString())) {
                return true;
            }
        }
        return false;
    }

    private boolean isJavaArchive(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".jar") || name.endsWith(".war");
    }

    private List<File> discoverSourceRoots(Path root) throws IOException {
        Set<Path> roots = new java.util.LinkedHashSet<>();
        for (Path path : listFiles(root, path -> SOURCE_EXTENSIONS.stream()
                .anyMatch(ext -> path.getFileName().toString().toLowerCase().endsWith(ext)))) {
            roots.add(sourceRootFor(root, path));
        }
        return roots.stream().map(Path::toFile).toList();
    }

    private Path sourceRootFor(Path archiveRoot, Path sourceFile) {
        Path relative = archiveRoot.relativize(sourceFile);
        int nameCount = relative.getNameCount();
        for (int i = 0; i < nameCount; i++) {
            String segment = relative.getName(i).toString();
            if ("sources".equals(segment) || "source".equals(segment)) return archiveRoot.resolve(relative.subpath(0, i + 1));
            if ("src".equals(segment) && i + 2 < nameCount
                    && "main".equals(relative.getName(i + 1).toString())
                    && "java".equals(relative.getName(i + 2).toString())) {
                return archiveRoot.resolve(relative.subpath(0, i + 3));
            }
        }
        return archiveRoot;
    }

    private void deleteIteratively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        List<Path> entries = listEntries(root);
        entries.sort(Comparator.reverseOrder());
        for (Path entry : entries) Files.deleteIfExists(entry);
    }

    private List<Path> listFiles(Path root, PathMatcher matcher) throws IOException {
        List<Path> result = new ArrayList<>();
        for (Path entry : listEntries(root)) {
            if (Files.isRegularFile(entry) && matcher.matches(entry)) result.add(entry);
        }
        result.sort(Comparator.naturalOrder());
        return result;
    }

    private List<Path> listEntries(Path root) throws IOException {
        List<Path> result = new ArrayList<>();
        ArrayDeque<Path> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            Path current = pending.removeFirst();
            result.add(current);
            if (Files.isDirectory(current)) {
                try (var entries = Files.newDirectoryStream(current)) {
                    for (Path entry : entries) pending.addLast(entry);
                }
            }
        }
        return result;
    }

    @FunctionalInterface
    private interface PathMatcher {
        boolean matches(Path path);
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
                           List<AssetSnapshot> coverages, List<AssetSnapshot> defects,
                           List<Baseline> baselines) {}

    public record GitSourceImport(String appId, String repositoryUrl, String username, String password,
                                  String branch, String commit, Integer maxFiles, Integer maxBytes) {}
    private record GitCredentials(String repoUrl, String username, String password, String branch, String commit) {}
    private record SourceSnapshot(String content, int totalFileCount, int sampledFileCount, boolean truncated) {}

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

    private SourceSnapshot summarizeSourceZip(File zipFile, Integer maxFiles, Integer maxBytes, SourceProfile profile) throws IOException {
        int fileLimit = maxFiles == null || maxFiles <= 0 ? 1000 : Math.min(maxFiles, 2000);
        int byteLimit = maxBytes == null || maxBytes <= 0 ? 20_000_000 : Math.min(maxBytes, 50_000_000);
        StringBuilder manifestBuilder = new StringBuilder();
        StringBuilder contentBuilder = new StringBuilder();
        int sampledCount = 0;
        int totalCount = 0;
        boolean truncated = false;
        try (ZipFile zip = new ZipFile(zipFile, StandardCharsets.UTF_8)) {
            List<? extends ZipEntry> entries = zip.stream()
                    .filter(entry -> !entry.isDirectory())
                    .filter(entry -> isSourceFile(entry.getName()))
                    .filter(entry -> profile == null || profile.matches(entry.getName()))
                    .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                    .toList();
            totalCount = entries.size();
            manifestBuilder.append(SOURCE_TREE_BEGIN).append('\n');
            for (ZipEntry entry : entries) {
                manifestBuilder.append(SOURCE_FILE_PREFIX).append(entry.getName()).append('\n');
            }
            manifestBuilder.append(SOURCE_TREE_END).append('\n');
            for (ZipEntry entry : entries) {
                if (sampledCount >= fileLimit || contentBuilder.length() >= byteLimit) {
                    truncated = true;
                    break;
                }
                String content = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
                int remaining = byteLimit - contentBuilder.length();
                if (remaining <= 0) {
                    truncated = true;
                    break;
                }
                if (content.length() > remaining) {
                    content = content.substring(0, remaining);
                    truncated = true;
                }
                contentBuilder.append("\n\n// FILE: ").append(entry.getName()).append('\n').append(content);
                sampledCount++;
            }
        }
        Assert.isTrue(totalCount > 0, "源码包中未找到符合当前源码工程设置的源码文件");
        StringBuilder builder = new StringBuilder(manifestBuilder);
        builder.append(contentBuilder);
        builder.append("\n\n// SNAPSHOT_ID: ").append(UUID.randomUUID());
        return new SourceSnapshot(builder.toString(), totalCount, sampledCount, truncated);
    }

    private boolean isSourceFile(String name) {
        String lower = name == null ? "" : name.toLowerCase();
        if (lower.contains("/node_modules/") || lower.contains("/dist/") || lower.contains("/build/")
                || lower.contains("/target/") || lower.contains("/.git/")) {
            return false;
        }
        return SOURCE_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private AppVo resolveSourceApp(String projectId, String appId) {
        if (!StringUtils.hasText(appId)) return null;
        AppVo app = appService.getApp(appId);
        Assert.notNull(app, "找不到指定应用");
        Assert.isTrue(projectId.equals(app.getCreateProjectId()), "应用不属于当前项目");
        return app;
    }

}
