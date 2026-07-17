package com.oAT.web.verification;

import com.oAT.web.esDao.StaticInfoRepository;
import com.oAT.web.esDao.entity.StaticSourceInfo;
import com.oAT.web.service.AppService;
import com.oAT.web.service.entity.AppVo;
import com.oAT.web.verification.model.VerificationModels;
import com.oAT.web.verification.model.VerificationModels.*;
import com.oAT.web.verification.storage.AssetContentStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Collectors;

@Service
public class VerificationService {
    private static final Logger logger = LoggerFactory.getLogger(VerificationService.class);

    private final VerificationRepository repository;
    private final StaticInfoRepository staticInfoRepository;
    private final VerificationAiOrchestrator aiOrchestrator;
    private final VerificationAiWriteBackComposer writeBackComposer;
    private final AssetContentStore assetContentStore;
    private final Executor verificationAiExecutor;
    private final AppService appService;

    public VerificationService(VerificationRepository repository, StaticInfoRepository staticInfoRepository,
                               VerificationAiOrchestrator aiOrchestrator, VerificationAiWriteBackComposer writeBackComposer,
                               AssetContentStore assetContentStore,
                               @Qualifier("verificationAiExecutor") Executor verificationAiExecutor,
                               AppService appService) {
        this.repository = repository;
        this.staticInfoRepository = staticInfoRepository;
        this.aiOrchestrator = aiOrchestrator;
        this.writeBackComposer = writeBackComposer;
        this.assetContentStore = assetContentStore;
        this.verificationAiExecutor = verificationAiExecutor;
        this.appService = appService;
    }

    public AssetSnapshot importAsset(String projectId, String userId, AssetType assetType, SourceType sourceType,
                                     String fileName, String content, String externalId, String externalUrl,
                                     String sourceVersion, Map<String, Object> metadata) {
        Assert.hasText(projectId, "projectId不能为空");
        Assert.notNull(assetType, "资产类型不能为空");
        Assert.hasText(content, "导入内容不能为空");
        LocalDateTime now = LocalDateTime.now();
        String assetId = UUID.randomUUID().toString();
        String contentHash = sha256(content);
        AssetContentStore.StoredContent stored = assetContentStore.store(
                new AssetContentStore.StoreCommand(projectId, assetId, contentHash, content));
        AssetSnapshot asset = new AssetSnapshot(assetId, projectId, assetType,
                sourceType == null ? SourceType.FILE : sourceType, externalId, externalUrl, sourceVersion,
                fileName, contentHash, null, stored.storageType(), stored.storageKey(), stored.contentSize(),
                stored.contentPreview(), metadata == null ? Map.of() : metadata,
                sourceType == SourceType.API || sourceType == SourceType.AGENT ? Freshness.LIVE : Freshness.MANUAL,
                userId, now);
        repository.saveAsset(asset);
        return asset;
    }

    public List<AssetSnapshot> assets(String projectId, AssetType type) {
        return repository.findAssets(projectId, type).stream().map(this::withoutContent).toList();
    }

    public AssetSnapshot updateAsset(String projectId, String assetId, String userId, UpdateAsset command) {
        Assert.notNull(command, "更新资料请求不能为空");
        AssetSnapshot existing = requiredAsset(projectId, assetId, null);
        String content = StringUtils.hasText(command.content()) ? command.content() : loadAssetContent(existing);
        Assert.hasText(content, "资料内容不能为空");
        String contentHash = sha256(content);
        AssetContentStore.StoredContent stored = assetContentStore.store(
                new AssetContentStore.StoreCommand(projectId, existing.id(), contentHash, content));
        AssetSnapshot updated = new AssetSnapshot(existing.id(), existing.projectId(), existing.assetType(),
                existing.sourceType(), textOrExisting(command.externalId(), existing.externalId()),
                textOrExisting(command.externalUrl(), existing.externalUrl()),
                textOrExisting(command.sourceVersion(), existing.sourceVersion()),
                textOrExisting(command.fileName(), existing.fileName()), contentHash, null,
                stored.storageType(), stored.storageKey(), stored.contentSize(), stored.contentPreview(),
                existing.metadata(), existing.freshness(), userId, LocalDateTime.now());
        Assert.isTrue(repository.updateAsset(updated), "找不到指定资料");
        return updated;
    }

    public void deleteAsset(String projectId, String assetId) {
        AssetSnapshot asset = requiredAsset(projectId, assetId, null);
        Assert.isTrue(!repository.isAssetReferenced(projectId, assetId), "该资料已被分析基线引用，不能直接删除");
        Assert.isTrue(repository.deleteAsset(projectId, assetId), "找不到指定资料");
        assetContentStore.delete(asset.storageKey());
    }

    public Baseline createBaseline(String projectId, String userId, CreateBaseline command) {
        AssetSnapshot requirement = optionalAsset(projectId, command.requirementAssetId(), AssetType.REQUIREMENT);
        AssetSnapshot testcase = optionalAsset(projectId, command.testcaseAssetId(), AssetType.TESTCASE);
        if (StringUtils.hasText(command.sourceAssetId())) requiredAsset(projectId, command.sourceAssetId(), AssetType.SOURCE);
        if (StringUtils.hasText(command.executionAssetId())) requiredAsset(projectId, command.executionAssetId(), AssetType.EXECUTION);
        if (StringUtils.hasText(command.coverageAssetId())) requiredAsset(projectId, command.coverageAssetId(), AssetType.COVERAGE);
        LocalDateTime now = LocalDateTime.now();
        Freshness freshness = requirement != null && testcase != null
                && requirement.freshness() == Freshness.LIVE && testcase.freshness() == Freshness.LIVE
                ? Freshness.LIVE : Freshness.MANUAL;
        Baseline baseline = new Baseline(UUID.randomUUID().toString(), projectId,
                StringUtils.hasText(command.name()) ? command.name().trim() : "AI验证 " + now.toLocalDate(),
                assetId(requirement), assetId(testcase), command.sourceAssetId(), command.executionAssetId(),
                command.coverageAssetId(), command.sourceAppId(), command.repositoryUrl(),
                command.sourceBranch(), command.sourceCommit(), VerificationModels.ANALYZER_VERSION,
                BaselineStatus.CREATED, freshness, userId, now, now);
        repository.saveBaseline(baseline);
        return baseline;
    }

    public List<Baseline> baselines(String projectId) {
        return repository.findBaselines(projectId);
    }

    public Baseline updateBaseline(String projectId, String baselineId, UpdateBaseline command) {
        Assert.notNull(command, "更新基线请求不能为空");
        Baseline existing = requiredBaseline(projectId, baselineId);
        String requirementAssetId = command.requirementAssetId();
        String testcaseAssetId = command.testcaseAssetId();
        AssetSnapshot requirement = optionalAsset(projectId, requirementAssetId, AssetType.REQUIREMENT);
        AssetSnapshot testcase = optionalAsset(projectId, testcaseAssetId, AssetType.TESTCASE);
        if (StringUtils.hasText(command.sourceAssetId())) requiredAsset(projectId, command.sourceAssetId(), AssetType.SOURCE);
        if (StringUtils.hasText(command.executionAssetId())) requiredAsset(projectId, command.executionAssetId(), AssetType.EXECUTION);
        if (StringUtils.hasText(command.coverageAssetId())) requiredAsset(projectId, command.coverageAssetId(), AssetType.COVERAGE);
        Freshness freshness = requirement != null && testcase != null
                && requirement.freshness() == Freshness.LIVE && testcase.freshness() == Freshness.LIVE
                ? Freshness.LIVE : Freshness.MANUAL;
        Baseline updated = new Baseline(existing.id(), existing.projectId(),
                StringUtils.hasText(command.name()) ? command.name().trim() : existing.name(),
                assetId(requirement), assetId(testcase), command.sourceAssetId(), command.executionAssetId(),
                command.coverageAssetId(), command.sourceAppId(), command.repositoryUrl(), command.sourceBranch(),
                command.sourceCommit(), existing.analyzerVersion(), BaselineStatus.CREATED, freshness,
                existing.createdBy(), existing.createTime(), LocalDateTime.now());
        Assert.isTrue(repository.updateBaseline(updated), "找不到指定分析基线");
        repository.deleteAnalysis(baselineId);
        return updated;
    }

    public void deleteBaseline(String projectId, String baselineId) {
        requiredBaseline(projectId, baselineId);
        Assert.isTrue(repository.deleteBaseline(projectId, baselineId), "找不到指定分析基线");
    }

    public BaselineDetail detail(String projectId, String baselineId) {
        Baseline baseline = requiredBaseline(projectId, baselineId);
        List<AcceptanceCriterion> criteria = repository.findCriteria(baselineId);
        List<TestcaseProjection> testcases = repository.findTestcases(baselineId);
        List<TraceLink> links = repository.findTraceLinks(baselineId);
        List<Finding> findings = repository.findFindings(baselineId);
        return new BaselineDetail(baseline, optionalAsset(projectId, baseline.requirementAssetId(), AssetType.REQUIREMENT),
                optionalAsset(projectId, baseline.testcaseAssetId(), AssetType.TESTCASE), criteria, testcases, links,
                findings, metrics(baseline, projectId, criteria, links, findings));
    }

    public AnalysisJob startAnalysis(String projectId, String baselineId, String userId) {
        Baseline baseline = requiredBaseline(projectId, baselineId);
        if (baseline.status() == BaselineStatus.ANALYZING) {
            return repository.findRunningAnalysisJob(projectId, baselineId)
                    .orElseThrow(() -> new IllegalArgumentException("该分析基线正在执行AI分析，请稍后刷新结果"));
        }
        repository.findRunningAnalysisJob(projectId, baselineId).ifPresent(job -> {
            throw new IllegalArgumentException("该分析基线已有AI分析任务在执行，请稍后刷新结果");
        });
        LocalDateTime now = LocalDateTime.now();
        AnalysisJob job = new AnalysisJob(UUID.randomUUID().toString(), projectId, baselineId,
                AnalysisJobStatus.QUEUED, "AI分析任务已进入队列", userId, now, now, null);
        repository.saveAnalysisJob(job);
        repository.updateBaselineStatus(baselineId, BaselineStatus.ANALYZING);
        try {
            verificationAiExecutor.execute(() -> runAnalysisJob(projectId, baselineId, job.id()));
        } catch (RejectedExecutionException e) {
            repository.updateAnalysisJobStatus(job.id(), AnalysisJobStatus.FAILED, "AI分析队列已满，请稍后重试", LocalDateTime.now());
            repository.updateBaselineStatus(baselineId, BaselineStatus.FAILED);
            throw new IllegalStateException("AI分析队列已满，请稍后重试", e);
        }
        return job;
    }

    public AnalysisJob analysisJob(String projectId, String jobId) {
        return repository.findAnalysisJob(projectId, jobId)
                .orElseThrow(() -> new IllegalArgumentException("找不到AI分析任务: " + jobId));
    }

    public AnalysisJob latestAnalysisJob(String projectId, String baselineId) {
        requiredBaseline(projectId, baselineId);
        return repository.findLatestAnalysisJob(projectId, baselineId)
                .orElseThrow(() -> new IllegalArgumentException("该分析基线还没有AI分析任务"));
    }

    private void runAnalysisJob(String projectId, String baselineId, String jobId) {
        updateAnalysisProgress(jobId, "正在准备分析资料");
        try {
            executeAnalysis(projectId, baselineId, jobId);
            repository.updateAnalysisJobStatus(jobId, AnalysisJobStatus.SUCCEEDED, "分析完成，结果已生成", LocalDateTime.now());
        } catch (RuntimeException e) {
            logger.error("AI分析任务失败, baselineId={}, jobId={}", baselineId, jobId, e);
            String message = readableAnalysisFailure(e);
            repository.updateAnalysisJobStatus(jobId, AnalysisJobStatus.FAILED,
                    message, LocalDateTime.now());
        }
    }

    private BaselineDetail executeAnalysis(String projectId, String baselineId, String jobId) {
        try {
            updateAnalysisProgress(jobId, "正在读取需求和测试用例资料");
            Baseline baseline = requiredBaseline(projectId, baselineId);
            AssetSnapshot requirement = optionalAsset(projectId, baseline.requirementAssetId(), AssetType.REQUIREMENT);
            AssetSnapshot testcase = optionalAsset(projectId, baseline.testcaseAssetId(), AssetType.TESTCASE);
            updateAnalysisProgress(jobId, "正在读取源码、执行、覆盖率和缺陷证据");
            Map<String, StaticSourceInfo> sources = loadSources(baseline.sourceAppId());
            AppVo sourceApp = StringUtils.hasText(baseline.sourceAppId()) ? appService.getApp(baseline.sourceAppId()) : null;
            String sourceAssetContent = StringUtils.hasText(baseline.sourceAssetId())
                    ? loadSourceAssetContent(requiredAsset(projectId, baseline.sourceAssetId(), AssetType.SOURCE), sourceApp) : "";
            String executionContent = StringUtils.hasText(baseline.executionAssetId())
                    ? loadAssetContent(requiredAsset(projectId, baseline.executionAssetId(), AssetType.EXECUTION)) : "";
            String coverageContent = StringUtils.hasText(baseline.coverageAssetId())
                    ? loadAssetContent(requiredAsset(projectId, baseline.coverageAssetId(), AssetType.COVERAGE)) : "";
            String defectContent = loadDefectAssets(projectId);
            updateAnalysisProgress(jobId, "正在请求 AI 分析并建立追溯关系");
            VerificationAiOrchestrator.AiVerificationResult result = aiOrchestrator.analyze(
                    new VerificationAiOrchestrator.AiVerificationInput(baselineId, loadOptionalAssetContent(requirement),
                            loadOptionalAssetContent(testcase), defectContent, sourceAssetContent, executionContent, coverageContent,
                            new ArrayList<>(sources.values())),
                    message -> updateAnalysisProgress(jobId, message));
            updateAnalysisProgress(jobId, "AI 分析完成，正在保存验收标准、追溯关系和问题");
            repository.replaceAnalysis(baselineId, result.criteria(), result.testcases(),
                    result.traceLinks(), result.findings());
            repository.updateBaselineStatus(baselineId, BaselineStatus.WAITING_REVIEW);
            return detail(projectId, baselineId);
        } catch (RuntimeException e) {
            repository.updateBaselineStatus(baselineId, BaselineStatus.FAILED);
            throw e;
        }
    }

    private void updateAnalysisProgress(String jobId, String message) {
        repository.updateAnalysisJobStatus(jobId, AnalysisJobStatus.RUNNING, message, null);
    }

    private String readableAnalysisFailure(RuntimeException error) {
        String detail = error.getMessage();
        if (detail != null && detail.contains("AI分析返回格式不合法")) {
            return "AI 已返回分析内容，但格式校验失败；系统已自动修复重试仍未成功。请检查模型是否支持 JSON 输出，或减少导入资料长度后重试。";
        }
        if (detail != null && detail.contains("AI分析没有返回结果")) {
            return "AI 没有返回分析内容，请检查模型服务、网络和模型配置后重试。";
        }
        if (detail != null && detail.contains("AI服务不可用")) {
            return "AI 服务当前不可用，请检查 AI 开关、接口地址、模型名称和 API Key。";
        }
        if (detail != null && detail.contains("AI 模型调用失败")) {
            return "AI 模型调用失败，请检查模型名称、API Key、网络连通性或稍后重试。详细原因已记录到服务端日志。";
        }
        return StringUtils.hasText(detail) ? "分析失败：" + detail : "分析失败，请检查资料和模型配置后重试。";
    }

    public List<MatrixRow> matrix(String projectId, String baselineId) {
        BaselineDetail detail = detail(projectId, baselineId);
        return detail.criteria().stream().map(ac -> {
            List<TraceLink> links = detail.traceLinks().stream().filter(v -> v.sourceId().equals(ac.id())).toList();
            Set<String> testcaseIds = links.stream().filter(v -> "TESTCASE".equals(v.targetType()))
                    .map(TraceLink::targetId).collect(Collectors.toSet());
            List<TestcaseProjection> tests = detail.testcases().stream().filter(v -> testcaseIds.contains(v.id())).toList();
            List<TraceLink> code = links.stream().filter(v -> "SOURCE_SYMBOL".equals(v.targetType())).toList();
            List<Finding> findings = detail.findings().stream().filter(v -> ac.id().equals(v.acId())).toList();
            Verdict verdict = verdict(findings);
            EvidenceLevel level = evidenceLevel(tests, links.stream().filter(v -> v.sourceId().equals(ac.id())).toList());
            return new MatrixRow(ac, tests, code, findings, verdict, level);
        }).toList();
    }

    public List<Finding> findings(String projectId, String baselineId) {
        requiredBaseline(projectId, baselineId);
        return repository.findFindings(baselineId);
    }

    public void reviewFinding(String projectId, String findingId, String userId, ReviewFinding command) {
        Assert.notNull(command.status(), "审核状态不能为空");
        Assert.isTrue(repository.reviewFinding(projectId, findingId, command.status(), userId,
                command.reason(), command.externalWorkItemUrl()), "找不到指定分析问题");
    }

    public WriteBackAction writeBackFinding(String projectId, String findingId, String userId, WriteBackFinding command) {
        Assert.notNull(command, "回写请求不能为空");
        Finding finding = repository.findFinding(projectId, findingId)
                .orElseThrow(() -> new IllegalArgumentException("找不到指定分析问题"));
        AcceptanceCriterion criterion = repository.findCriteria(finding.baselineId()).stream()
                .filter(item -> item.id().equals(finding.acId()))
                .findFirst()
                .orElse(null);
        List<TraceLink> traceLinks = repository.findTraceLinks(finding.baselineId()).stream()
                .filter(item -> finding.acId() != null && finding.acId().equals(item.sourceId()))
                .toList();
        Set<String> testcaseIds = traceLinks.stream()
                .filter(item -> "TESTCASE".equals(item.targetType()))
                .map(TraceLink::targetId)
                .collect(Collectors.toSet());
        List<TestcaseProjection> testcases = repository.findTestcases(finding.baselineId()).stream()
                .filter(item -> testcaseIds.contains(item.id()) || testcaseIds.contains(item.externalKey()))
                .toList();
        String aiMessage = writeBackComposer.compose(new VerificationAiWriteBackComposer.WriteBackInput(
                finding, criterion, testcases, traceLinks, command.targetRole(), command.externalUrl(), command.message()));
        Assert.isTrue(repository.reviewFinding(projectId, findingId, ReviewStatus.WRITTEN_BACK, userId,
                aiMessage, command.externalUrl()), "找不到指定分析问题");
        WriteBackAction action = new WriteBackAction(UUID.randomUUID().toString(), projectId, finding.baselineId(),
                findingId, StringUtils.hasText(command.connectorType()) ? command.connectorType() : "ai-writeback",
                command.externalUrl(), "AI_GENERATED", aiMessage, userId, LocalDateTime.now());
        repository.saveWriteBackAction(action);
        return action;
    }

    public List<WriteBackAction> writeBackActions(String projectId, String baselineId) {
        requiredBaseline(projectId, baselineId);
        return repository.findWriteBackActions(baselineId);
    }

    public void reviewTraceLink(String projectId, String traceLinkId, ReviewTraceLink command) {
        Assert.notNull(command.status(), "审核状态不能为空");
        Assert.isTrue(command.status() == ReviewStatus.CONFIRMED || command.status() == ReviewStatus.REJECTED
                        || command.status() == ReviewStatus.STALE,
                "追溯关系只支持确认、驳回或标记过期");
        Assert.isTrue(repository.reviewTraceLink(projectId, traceLinkId, command.status()), "找不到指定追溯关系");
    }

    public void markBaselineStale(String projectId, String baselineId) {
        requiredBaseline(projectId, baselineId);
        repository.markBaselineStale(baselineId);
    }

    private Map<String, StaticSourceInfo> loadSources(String appId) {
        if (!StringUtils.hasText(appId)) return Map.of();
        Map<String, StaticSourceInfo> result = new LinkedHashMap<>();
        for (StaticSourceInfo item : staticInfoRepository.findByAppId(appId))
            if (item.getClassInfo() != null) result.put(item.getClassInfo().getClassName(), item);
        return result;
    }

    private String loadDefectAssets(String projectId) {
        List<AssetSnapshot> defects = repository.findAssets(projectId, AssetType.DEFECT);
        if (defects.isEmpty()) return "";
        StringBuilder result = new StringBuilder();
        for (AssetSnapshot defect : defects) {
            result.append("资料: ").append(value(defect.fileName())).append('\n');
            if (StringUtils.hasText(defect.externalId())) result.append("外部ID: ").append(defect.externalId()).append('\n');
            if (StringUtils.hasText(defect.externalUrl())) result.append("外部链接: ").append(defect.externalUrl()).append('\n');
            result.append(value(loadAssetContent(defect))).append("\n\n");
        }
        return result.toString();
    }

    private String loadAssetContent(AssetSnapshot asset) {
        if (StringUtils.hasText(asset.storageKey())) {
            String stored = assetContentStore.load(asset.storageKey());
            if (StringUtils.hasText(stored)) return stored;
        }
        return value(asset.content());
    }

    private String loadOptionalAssetContent(AssetSnapshot asset) {
        return asset == null ? "" : loadAssetContent(asset);
    }

    private String loadSourceAssetContent(AssetSnapshot asset, AppVo sourceApp) {
        return SourceAssetFilter.filterContent(loadAssetContent(asset), SourceAssetFilter.fromApp(sourceApp));
    }

    private AssetSnapshot withoutContent(AssetSnapshot asset) {
        return new AssetSnapshot(asset.id(), asset.projectId(), asset.assetType(), asset.sourceType(),
                asset.externalId(), asset.externalUrl(), asset.sourceVersion(), asset.fileName(), asset.contentHash(),
                null, asset.storageType(), asset.storageKey(), asset.contentSize(),
                StringUtils.hasText(asset.contentPreview()) ? asset.contentPreview() : preview(asset.content()),
                asset.metadata(), asset.freshness(), asset.importedBy(), asset.capturedAt());
    }

    private String preview(String content) {
        String safe = value(content);
        return safe.length() <= 600 ? safe : safe.substring(0, 600);
    }

    private Verdict verdict(List<Finding> findings) {
        if (findings.stream().anyMatch(v -> v.verdict() == Verdict.NOT_SATISFIED)) return Verdict.NOT_SATISFIED;
        if (findings.stream().anyMatch(v -> v.verdict() == Verdict.AMBIGUOUS)) return Verdict.AMBIGUOUS;
        if (findings.stream().anyMatch(v -> v.verdict() == Verdict.NOT_VERIFIABLE)) return Verdict.NOT_VERIFIABLE;
        if (findings.stream().anyMatch(v -> v.verdict() == Verdict.PARTIAL)) return Verdict.PARTIAL;
        if (findings.stream().anyMatch(v -> v.verdict() == Verdict.STATICALLY_CONSISTENT)) return Verdict.STATICALLY_CONSISTENT;
        if (findings.stream().anyMatch(v -> v.verdict() == Verdict.SATISFIED)) return Verdict.SATISFIED;
        return Verdict.NOT_VERIFIABLE;
    }

    private Metrics metrics(Baseline baseline, String projectId, List<AcceptanceCriterion> criteria,
                            List<TraceLink> links, List<Finding> findings) {
        Set<String> tested = links.stream().filter(v -> "TESTCASE".equals(v.targetType())).map(TraceLink::sourceId).collect(Collectors.toSet());
        Set<String> implemented = links.stream().filter(v -> "SOURCE_SYMBOL".equals(v.targetType())).map(TraceLink::sourceId).collect(Collectors.toSet());
        Set<String> executed = links.stream().filter(v -> "EXECUTION".equals(v.targetType())).map(TraceLink::sourceId).collect(Collectors.toSet());
        Set<String> runtimeCovered = links.stream().filter(v -> "COVERAGE".equals(v.targetType())).map(TraceLink::sourceId).collect(Collectors.toSet());
        Set<String> closed = new HashSet<>(tested); closed.retainAll(implemented);
        int total = criteria.size();
        long open = findings.stream().filter(v -> v.reviewStatus() == ReviewStatus.PENDING || v.reviewStatus() == ReviewStatus.CONFIRMED).count();
        int staticCodeCount = staticCodeCount(baseline, projectId);
        int dynamicCodeCount = dynamicCodeCount(baseline, projectId);
        return new Metrics(total, tested.size(), implemented.size(), executed.size(), runtimeCovered.size(),
                closed.size(), (int) open, rate(tested.size(), total), rate(implemented.size(), total),
                rate(executed.size(), total), rate(runtimeCovered.size(), total), rate(closed.size(), total),
                staticCodeCount, dynamicCodeCount);
    }

    private int staticCodeCount(Baseline baseline, String projectId) {
        int sourceFileCount = 0;
        String sourceAppId = baseline.sourceAppId();
        if (StringUtils.hasText(baseline.sourceAssetId())) {
            AssetSnapshot sourceAsset = requiredAsset(projectId, baseline.sourceAssetId(), AssetType.SOURCE);
            sourceFileCount = metadataInt(sourceAsset.metadata(), "totalFileCount");
            if (sourceFileCount == 0) sourceFileCount = metadataInt(sourceAsset.metadata(), "fileCount");
            if (!StringUtils.hasText(sourceAppId)) sourceAppId = metadataText(sourceAsset.metadata(), "appId");
            if (sourceFileCount == 0) {
                String content = loadAssetContent(sourceAsset);
                sourceFileCount = countMarkers(content, "// FILE:");
                if (sourceFileCount == 0 && StringUtils.hasText(content)) sourceFileCount = 1;
            }
        }
        int staticSymbolCount = 0;
        if (StringUtils.hasText(sourceAppId)) {
            for (StaticSourceInfo source : staticInfoRepository.findByAppId(sourceAppId)) {
                if (source == null || source.getClassInfo() == null) continue;
                staticSymbolCount++;
                if (source.getClassInfo().getMethodMaps() != null) {
                    staticSymbolCount += source.getClassInfo().getMethodMaps().size();
                }
            }
        }
        return Math.max(sourceFileCount, staticSymbolCount);
    }

    private String metadataText(Map<String, Object> metadata, String key) {
        if (metadata == null || !metadata.containsKey(key)) return null;
        Object value = metadata.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private int metadataInt(Map<String, Object> metadata, String key) {
        if (metadata == null || !metadata.containsKey(key)) return 0;
        Object value = metadata.get(key);
        if (value instanceof Number number) return Math.max(0, number.intValue());
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Math.max(0, Integer.parseInt(text.trim()));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private int dynamicCodeCount(Baseline baseline, String projectId) {
        int count = 0;
        if (StringUtils.hasText(baseline.executionAssetId())) {
            String content = loadAssetContent(requiredAsset(projectId, baseline.executionAssetId(), AssetType.EXECUTION));
            count += Math.max(1, countNonBlankDataLines(content));
        }
        if (StringUtils.hasText(baseline.coverageAssetId())) {
            String content = loadAssetContent(requiredAsset(projectId, baseline.coverageAssetId(), AssetType.COVERAGE));
            int coverageFiles = countMarkers(content, "// COVERAGE_FILE:");
            count += coverageFiles > 0 ? coverageFiles : Math.max(1, countNonBlankDataLines(content));
        }
        return count;
    }

    private int countMarkers(String content, String marker) {
        if (!StringUtils.hasText(content)) return 0;
        int count = 0;
        for (String line : content.split("\\R")) {
            if (line.trim().startsWith(marker)) count++;
        }
        return count;
    }

    private int countNonBlankDataLines(String content) {
        if (!StringUtils.hasText(content)) return 0;
        int count = 0;
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith("//")) count++;
        }
        return count;
    }

    private EvidenceLevel evidenceLevel(List<TestcaseProjection> tests, List<TraceLink> links) {
        if (links.stream().anyMatch(v -> v.evidenceLevel() == EvidenceLevel.E4)) return EvidenceLevel.E4;
        if (links.stream().anyMatch(v -> v.evidenceLevel() == EvidenceLevel.E3)) return EvidenceLevel.E3;
        if (links.stream().anyMatch(v -> v.evidenceLevel() == EvidenceLevel.E2)) return EvidenceLevel.E2;
        return tests.isEmpty() ? EvidenceLevel.E0 : EvidenceLevel.E1;
    }

    private AssetSnapshot requiredAsset(String projectId, String id, AssetType type) {
        AssetSnapshot asset = repository.findAsset(projectId, id).orElseThrow(() -> new IllegalArgumentException("找不到资产: " + id));
        if (type != null) {
            Assert.isTrue(asset.assetType() == type, "资产类型不匹配: " + id);
        }
        return asset;
    }

    private AssetSnapshot optionalAsset(String projectId, String id, AssetType type) {
        if (!StringUtils.hasText(id)) {
            return null;
        }
        return requiredAsset(projectId, id, type);
    }

    private String assetId(AssetSnapshot asset) {
        return asset == null ? null : asset.id();
    }

    private Baseline requiredBaseline(String projectId, String id) {
        return repository.findBaseline(projectId, id).orElseThrow(() -> new IllegalArgumentException("找不到分析基线: " + id));
    }

    private String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(); for (byte b : digest) hex.append(String.format("%02x", b)); return hex.toString();
        } catch (Exception e) { throw new IllegalStateException("SHA-256不可用", e); }
    }

    private double rate(int value, int total) { return total == 0 ? 0 : Math.round((double) value / total * 10000) / 10000.0; }
    private String value(String value) { return value == null ? "" : value; }
    private String textOrExisting(String value, String existing) { return StringUtils.hasText(value) ? value.trim() : existing; }

    public record CreateBaseline(String name, String requirementAssetId, String testcaseAssetId, String sourceAssetId,
                                 String executionAssetId, String coverageAssetId, String sourceAppId,
                                 String repositoryUrl, String sourceBranch, String sourceCommit) {}
    public record UpdateAsset(String fileName, String content, String externalId, String externalUrl, String sourceVersion) {}
    public record UpdateBaseline(String name, String requirementAssetId, String testcaseAssetId, String sourceAssetId,
                                 String executionAssetId, String coverageAssetId, String sourceAppId,
                                 String repositoryUrl, String sourceBranch, String sourceCommit) {}
    public record ReviewFinding(ReviewStatus status, String reason, String externalWorkItemUrl) {}
    public record ReviewTraceLink(ReviewStatus status) {}
    public record WriteBackFinding(String connectorType, String externalUrl, String message, String targetRole) {}
}
