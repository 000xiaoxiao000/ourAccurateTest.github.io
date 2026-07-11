package com.oAT.web.verification;

import com.oAT.web.esDao.StaticInfoRepository;
import com.oAT.web.esDao.entity.StaticSourceInfo;
import com.oAT.web.verification.model.VerificationModels;
import com.oAT.web.verification.model.VerificationModels.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class VerificationService {
    private static final Pattern WORD = Pattern.compile("[\\p{IsHan}]{2,}|[a-zA-Z][a-zA-Z0-9_]{2,}|\\d+");
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");

    private final VerificationRepository repository;
    private final VerificationProjectionParser parser;
    private final StaticInfoRepository staticInfoRepository;
    private final VerificationAiAnalyzer aiAnalyzer;

    public VerificationService(VerificationRepository repository, VerificationProjectionParser parser,
                               StaticInfoRepository staticInfoRepository, VerificationAiAnalyzer aiAnalyzer) {
        this.repository = repository;
        this.parser = parser;
        this.staticInfoRepository = staticInfoRepository;
        this.aiAnalyzer = aiAnalyzer;
    }

    public AssetSnapshot importAsset(String projectId, String userId, AssetType assetType, SourceType sourceType,
                                     String fileName, String content, String externalId, String externalUrl,
                                     String sourceVersion, Map<String, Object> metadata) {
        Assert.hasText(projectId, "projectId不能为空");
        Assert.notNull(assetType, "资产类型不能为空");
        Assert.hasText(content, "导入内容不能为空");
        LocalDateTime now = LocalDateTime.now();
        AssetSnapshot asset = new AssetSnapshot(UUID.randomUUID().toString(), projectId, assetType,
                sourceType == null ? SourceType.FILE : sourceType, externalId, externalUrl, sourceVersion,
                fileName, sha256(content), content, metadata == null ? Map.of() : metadata,
                sourceType == SourceType.API || sourceType == SourceType.AGENT ? Freshness.LIVE : Freshness.MANUAL,
                userId, now);
        repository.saveAsset(asset);
        return asset;
    }

    public List<AssetSnapshot> assets(String projectId, AssetType type) {
        return repository.findAssets(projectId, type);
    }

    public Baseline createBaseline(String projectId, String userId, CreateBaseline command) {
        AssetSnapshot requirement = requiredAsset(projectId, command.requirementAssetId(), AssetType.REQUIREMENT);
        AssetSnapshot testcase = requiredAsset(projectId, command.testcaseAssetId(), AssetType.TESTCASE);
        if (StringUtils.hasText(command.sourceAssetId())) requiredAsset(projectId, command.sourceAssetId(), AssetType.SOURCE);
        if (StringUtils.hasText(command.executionAssetId())) requiredAsset(projectId, command.executionAssetId(), AssetType.EXECUTION);
        if (StringUtils.hasText(command.coverageAssetId())) requiredAsset(projectId, command.coverageAssetId(), AssetType.COVERAGE);
        LocalDateTime now = LocalDateTime.now();
        Freshness freshness = requirement.freshness() == Freshness.LIVE && testcase.freshness() == Freshness.LIVE
                ? Freshness.LIVE : Freshness.MANUAL;
        Baseline baseline = new Baseline(UUID.randomUUID().toString(), projectId,
                StringUtils.hasText(command.name()) ? command.name().trim() : "AI验证 " + now.toLocalDate(),
                requirement.id(), testcase.id(), command.sourceAssetId(), command.executionAssetId(),
                command.coverageAssetId(), command.sourceAppId(), command.repositoryUrl(),
                command.sourceBranch(), command.sourceCommit(), VerificationModels.ANALYZER_VERSION,
                BaselineStatus.CREATED, freshness, userId, now, now);
        repository.saveBaseline(baseline);
        return baseline;
    }

    public List<Baseline> baselines(String projectId) {
        return repository.findBaselines(projectId);
    }

    public BaselineDetail detail(String projectId, String baselineId) {
        Baseline baseline = requiredBaseline(projectId, baselineId);
        List<AcceptanceCriterion> criteria = repository.findCriteria(baselineId);
        List<TestcaseProjection> testcases = repository.findTestcases(baselineId);
        List<TraceLink> links = repository.findTraceLinks(baselineId);
        List<Finding> findings = repository.findFindings(baselineId);
        return new BaselineDetail(baseline, requiredAsset(projectId, baseline.requirementAssetId(), AssetType.REQUIREMENT),
                requiredAsset(projectId, baseline.testcaseAssetId(), AssetType.TESTCASE), criteria, testcases, links,
                findings, metrics(criteria, links, findings));
    }

    @Transactional
    public BaselineDetail analyze(String projectId, String baselineId) {
        Baseline baseline = requiredBaseline(projectId, baselineId);
        repository.updateBaselineStatus(baselineId, BaselineStatus.ANALYZING);
        try {
            AssetSnapshot requirement = requiredAsset(projectId, baseline.requirementAssetId(), AssetType.REQUIREMENT);
            AssetSnapshot testcase = requiredAsset(projectId, baseline.testcaseAssetId(), AssetType.TESTCASE);
            List<AcceptanceCriterion> criteria = parser.parseRequirements(baselineId, requirement.content());
            List<TestcaseProjection> testcases = parser.parseTestcases(baselineId, testcase.fileName(), testcase.content());
            List<TraceLink> links = new ArrayList<>();
            List<Finding> findings = new ArrayList<>();
            Map<String, StaticSourceInfo> sources = loadSources(baseline.sourceAppId());
            String sourceAssetContent = StringUtils.hasText(baseline.sourceAssetId())
                    ? requiredAsset(projectId, baseline.sourceAssetId(), AssetType.SOURCE).content() : "";
            String executionContent = StringUtils.hasText(baseline.executionAssetId())
                    ? requiredAsset(projectId, baseline.executionAssetId(), AssetType.EXECUTION).content() : "";
            String coverageContent = StringUtils.hasText(baseline.coverageAssetId())
                    ? requiredAsset(projectId, baseline.coverageAssetId(), AssetType.COVERAGE).content() : "";
            for (AcceptanceCriterion criterion : criteria) {
                List<TestcaseProjection> matchedTests = matchTestcases(criterion, testcases);
                matchedTests.forEach(test -> links.add(testLink(baselineId, criterion, test)));
                List<StaticSourceInfo> matchedSources = matchSources(criterion, sources.values());
                matchedSources.stream().limit(5).forEach(source -> links.add(codeLink(baselineId, criterion, source)));
                boolean hasSourceAssetMatch = false;
                if (matchedSources.isEmpty() && hasSourceAssetEvidence(criterion, sourceAssetContent)) {
                    links.add(sourceAssetLink(baselineId, criterion, baseline.sourceAssetId(), sourceAssetContent));
                    hasSourceAssetMatch = true;
                }
                addRuntimeEvidenceLinks(baselineId, criterion, matchedTests, executionContent, coverageContent, links);
                buildFindings(baselineId, criterion, matchedTests, matchedSources, hasSourceAssetMatch, findings);
                findings.addAll(aiAnalyzer.analyze(baselineId, criterion, matchedTests, matchedSources,
                        hasSourceAssetMatch ? sourceAssetContent : ""));
            }
            addUnlinkedTestcaseFindings(baselineId, testcases, links, findings);
            repository.replaceAnalysis(baselineId, criteria, testcases, links, deduplicateFindings(findings));
            repository.updateBaselineStatus(baselineId, BaselineStatus.WAITING_REVIEW);
            return detail(projectId, baselineId);
        } catch (RuntimeException e) {
            repository.updateBaselineStatus(baselineId, BaselineStatus.FAILED);
            throw e;
        }
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
            Verdict verdict = verdict(ac, tests, code, findings);
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
        Assert.hasText(command.externalUrl(), "外部事项链接不能为空");
        Finding finding = repository.findFinding(projectId, findingId)
                .orElseThrow(() -> new IllegalArgumentException("找不到指定分析问题"));
        Assert.isTrue(repository.reviewFinding(projectId, findingId, ReviewStatus.WRITTEN_BACK, userId,
                command.message(), command.externalUrl()), "找不到指定分析问题");
        WriteBackAction action = new WriteBackAction(UUID.randomUUID().toString(), projectId, finding.baselineId(),
                findingId, StringUtils.hasText(command.connectorType()) ? command.connectorType() : "link-only",
                command.externalUrl(), "RECORDED", command.message(), userId, LocalDateTime.now());
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

    public GateResult evaluateGate(String projectId, String baselineId, GatePolicy policy) {
        BaselineDetail detail = detail(projectId, baselineId);
        GatePolicy effective = policy == null ? GatePolicy.defaults() : policy;
        List<String> reasons = new ArrayList<>();
        if (detail.metrics().testcaseCoverageRate() < effective.minimumTestcaseCoverage())
            reasons.add("AC用例覆盖率低于 " + percent(effective.minimumTestcaseCoverage()));
        if (detail.metrics().implementationCoverageRate() < effective.minimumImplementationCoverage())
            reasons.add("AC实现覆盖率低于 " + percent(effective.minimumImplementationCoverage()));
        if (detail.metrics().executionEvidenceRate() < effective.minimumExecutionEvidence())
            reasons.add("AC执行证据低于 " + percent(effective.minimumExecutionEvidence()));
        if (detail.metrics().runtimeCoverageRate() < effective.minimumRuntimeCoverage())
            reasons.add("AC运行覆盖证据低于 " + percent(effective.minimumRuntimeCoverage()));
        long high = detail.findings().stream().filter(v -> v.reviewStatus() != ReviewStatus.REJECTED)
                .filter(v -> v.severity() == Severity.CRITICAL || v.severity() == Severity.HIGH).count();
        if (high > effective.maximumOpenHighFindings()) reasons.add("高风险未关闭问题 " + high + " 个");
        if (detail.baseline().freshness() == Freshness.STALE) reasons.add("分析基线已过期");
        GateStatus status = reasons.isEmpty() ? GateStatus.PASSED : effective.blocking() ? GateStatus.FAILED : GateStatus.WARNING;
        GateResult result = new GateResult(UUID.randomUUID().toString(), baselineId, status,
                Map.of("minimumTestcaseCoverage", effective.minimumTestcaseCoverage(),
                        "minimumImplementationCoverage", effective.minimumImplementationCoverage(),
                        "minimumExecutionEvidence", effective.minimumExecutionEvidence(),
                        "minimumRuntimeCoverage", effective.minimumRuntimeCoverage(),
                        "maximumOpenHighFindings", effective.maximumOpenHighFindings(), "blocking", effective.blocking()),
                detail.metrics(), reasons, LocalDateTime.now());
        repository.saveGateResult(result);
        return result;
    }

    private void buildFindings(String baselineId, AcceptanceCriterion ac, List<TestcaseProjection> tests,
                               List<StaticSourceInfo> sources, boolean hasSourceAssetMatch, List<Finding> findings) {
        if (ac.ambiguity()) findings.add(finding(baselineId, ac, "AMBIGUOUS_REQUIREMENT", Perspective.PRODUCT,
                Severity.MEDIUM, "需求描述存在模糊词", "验收标准包含无法直接量化的表达：" + ac.content(),
                "请在需求事实源中补充明确阈值或可观察结果", 0.78, EvidenceLevel.E1, Verdict.AMBIGUOUS));
        if (tests.isEmpty()) findings.add(finding(baselineId, ac, "MISSING_TESTCASE", Perspective.TEST,
                Severity.HIGH, "验收标准缺少测试用例", ac.acKey() + " 没有找到可验证该规则的测试用例",
                "补充正常、边界和反向场景，并提供可判定预期", 0.82, EvidenceLevel.E1, Verdict.NOT_SATISFIED));
        else if (tests.stream().allMatch(v -> !StringUtils.hasText(v.expected())))
            findings.add(finding(baselineId, ac, "WEAK_ASSERTION", Perspective.TEST, Severity.HIGH,
                    "关联用例缺少明确预期", "已找到关联用例，但没有可观察的预期结果",
                    "为关键业务结果、错误码和副作用补充断言", 0.88, EvidenceLevel.E1, Verdict.PARTIAL));
        if (hasNumericConflict(ac, tests)) findings.add(finding(baselineId, ac, "WRONG_EXPECTATION",
                Perspective.TEST, Severity.HIGH, "用例预期可能与需求约束冲突",
                "需求与关联用例包含不同的数值约束", "核对边界、次数、长度和时限后修正用例预期",
                0.72, EvidenceLevel.E1, Verdict.NOT_SATISFIED));
        if (sources.isEmpty() && !hasSourceAssetMatch) findings.add(finding(baselineId, ac, "MISSING_IMPLEMENTATION",
                Perspective.DEVELOPMENT, Severity.HIGH, "未找到可信实现证据",
                "当前源码索引中没有找到与 " + ac.acKey() + " 匹配的类或方法",
                "确认源码快照完整后，定位并补充实现或人工关联真实符号", 0.60,
                EvidenceLevel.E0, Verdict.NOT_VERIFIABLE));
    }

    private void addUnlinkedTestcaseFindings(String baselineId, List<TestcaseProjection> tests,
                                             List<TraceLink> links, List<Finding> findings) {
        Set<String> linked = links.stream().filter(v -> "TESTCASE".equals(v.targetType()))
                .map(TraceLink::targetId).collect(Collectors.toSet());
        for (TestcaseProjection test : tests) if (!linked.contains(test.id())) {
            findings.add(new Finding(UUID.randomUUID().toString(), baselineId, null, "TRACEABILITY_BREAK",
                    Perspective.CROSS, Severity.MEDIUM, "测试用例未关联到验收标准",
                    test.externalKey() + " " + test.title() + " 没有找到需求来源", "在外部平台补充需求 ID 或人工确认关联",
                    0.75, EvidenceLevel.E0, Verdict.NOT_VERIFIABLE, ReviewStatus.PENDING,
                    List.of(Map.of("testcaseId", test.externalKey(), "locator", test.sourceLocator())), null, null, null));
        }
    }

    private List<Finding> deduplicateFindings(List<Finding> findings) {
        Map<String, Finding> result = new LinkedHashMap<>();
        for (Finding finding : findings) {
            String key = value(finding.acId()) + "|" + finding.findingType() + "|" + finding.perspective();
            Finding existing = result.get(key);
            if (existing == null || finding.confidence() > existing.confidence()) result.put(key, finding);
        }
        return new ArrayList<>(result.values());
    }

    private List<TestcaseProjection> matchTestcases(AcceptanceCriterion ac, List<TestcaseProjection> tests) {
        String requirement = ac.requirementKey().toLowerCase(Locale.ROOT);
        String acKey = value(ac.acKey()).toLowerCase(Locale.ROOT);
        return tests.stream().filter(test -> {
            String refs = value(test.requirementRefs()).toLowerCase(Locale.ROOT);
            if (StringUtils.hasText(acKey) && refs.contains(acKey)) return true;
            if (refs.contains(requirement) && similarity(ac.content(), test.title() + " " + test.steps() + " " + test.expected()) >= 0.12)
                return true;
            return similarity(ac.content(), test.title() + " " + test.steps() + " " + test.expected()) >= 0.18;
        }).toList();
    }

    private List<StaticSourceInfo> matchSources(AcceptanceCriterion ac, Iterable<StaticSourceInfo> sources) {
        List<StaticSourceInfo> result = new ArrayList<>();
        for (StaticSourceInfo source : sources) {
            if (source.getClassInfo() == null) continue;
            StringBuilder searchable = new StringBuilder(value(source.getClassInfo().getClassName())).append(' ')
                    .append(value(source.getClassInfo().getSourceCode()));
            if (source.getClassInfo().getMethodMaps() != null) searchable.append(' ').append(source.getClassInfo().getMethodMaps().keySet());
            if (similarity(ac.content() + " " + ac.title(), searchable.toString()) >= 0.10) result.add(source);
        }
        return result;
    }

    private Map<String, StaticSourceInfo> loadSources(String appId) {
        if (!StringUtils.hasText(appId)) return Map.of();
        Map<String, StaticSourceInfo> result = new LinkedHashMap<>();
        for (StaticSourceInfo item : staticInfoRepository.findByAppId(appId))
            if (item.getClassInfo() != null) result.put(item.getClassInfo().getClassName(), item);
        return result;
    }

    private TraceLink testLink(String baselineId, AcceptanceCriterion ac, TestcaseProjection test) {
        boolean explicit = value(test.requirementRefs()).toLowerCase(Locale.ROOT).contains(ac.requirementKey().toLowerCase(Locale.ROOT));
        double confidence = explicit ? 0.98 : Math.min(0.90, 0.55 + similarity(ac.content(), test.title() + " " + test.expected()));
        return new TraceLink(UUID.randomUUID().toString(), baselineId, "AC", ac.id(), "TESTCASE", test.id(),
                "VERIFIED_BY", explicit ? "EXPLICIT_ID" : "SEMANTIC_RULE", confidence,
                EvidenceLevel.E1, explicit ? ReviewStatus.CONFIRMED : ReviewStatus.PENDING,
                Map.of("ac", ac.content(), "testcase", test.externalKey(), "locator", test.sourceLocator()));
    }

    private TraceLink codeLink(String baselineId, AcceptanceCriterion ac, StaticSourceInfo source) {
        String className = source.getClassInfo().getClassName();
        return new TraceLink(UUID.randomUUID().toString(), baselineId, "AC", ac.id(), "SOURCE_SYMBOL", className,
                "IMPLEMENTED_BY", "STATIC_SOURCE", 0.68, EvidenceLevel.E2, ReviewStatus.PENDING,
                Map.of("className", className, "sourceId", value(source.getId())));
    }

    private TraceLink sourceAssetLink(String baselineId, AcceptanceCriterion ac, String sourceAssetId, String content) {
        return new TraceLink(UUID.randomUUID().toString(), baselineId, "AC", ac.id(), "SOURCE_SYMBOL",
                "SOURCE_ASSET:" + sourceAssetId, "IMPLEMENTED_BY", "SOURCE_SNAPSHOT", 0.56,
                EvidenceLevel.E2, ReviewStatus.PENDING,
                Map.of("sourceAssetId", sourceAssetId, "matchedBy", "source_snapshot_rule",
                        "snippet", content.substring(0, Math.min(content.length(), 600))));
    }

    private void addRuntimeEvidenceLinks(String baselineId, AcceptanceCriterion ac, List<TestcaseProjection> tests,
                                         String executionContent, String coverageContent, List<TraceLink> links) {
        boolean hasExecution = StringUtils.hasText(executionContent) && tests.stream().anyMatch(test ->
                containsIgnoreCase(executionContent, test.externalKey()) || containsIgnoreCase(executionContent, test.title()));
        if (hasExecution) {
            links.add(new TraceLink(UUID.randomUUID().toString(), baselineId, "AC", ac.id(), "EXECUTION",
                    "EXECUTION_REPORT", "PROVEN_BY", "EXECUTION_REPORT", 0.72, EvidenceLevel.E3,
                    ReviewStatus.PENDING, Map.of("matchedTestcases", tests.stream().map(TestcaseProjection::externalKey).toList())));
        }
        boolean hasCoverage = StringUtils.hasText(coverageContent) && (similarity(ac.content(), coverageContent) >= 0.04
                || tests.stream().anyMatch(test -> containsIgnoreCase(coverageContent, test.externalKey())
                || containsIgnoreCase(coverageContent, test.title())));
        if (hasCoverage) {
            links.add(new TraceLink(UUID.randomUUID().toString(), baselineId, "AC", ac.id(), "COVERAGE",
                    "COVERAGE_REPORT", "COVERED_BY", "COVERAGE_REPORT", 0.64, EvidenceLevel.E4,
                    ReviewStatus.PENDING, Map.of("matchedBy", "report_text")));
        }
    }

    private Finding finding(String baselineId, AcceptanceCriterion ac, String type, Perspective perspective,
                            Severity severity, String title, String description, String suggestion, double confidence,
                            EvidenceLevel level, Verdict verdict) {
        return new Finding(UUID.randomUUID().toString(), baselineId, ac.id(), type, perspective, severity, title,
                description, suggestion, confidence, level, verdict, ReviewStatus.PENDING,
                List.of(Map.of("requirementKey", ac.requirementKey(), "acKey", ac.acKey(),
                        "content", ac.content(), "locator", ac.sourceLocator())), null, null, null);
    }

    private Verdict verdict(AcceptanceCriterion ac, List<TestcaseProjection> tests, List<TraceLink> code, List<Finding> findings) {
        if (ac.ambiguity()) return Verdict.AMBIGUOUS;
        if (findings.stream().anyMatch(v -> v.verdict() == Verdict.NOT_SATISFIED)) return Verdict.NOT_SATISFIED;
        if (tests.isEmpty() || code.isEmpty()) return Verdict.PARTIAL;
        return Verdict.STATICALLY_CONSISTENT;
    }

    private Metrics metrics(List<AcceptanceCriterion> criteria, List<TraceLink> links, List<Finding> findings) {
        Set<String> tested = links.stream().filter(v -> "TESTCASE".equals(v.targetType())).map(TraceLink::sourceId).collect(Collectors.toSet());
        Set<String> implemented = links.stream().filter(v -> "SOURCE_SYMBOL".equals(v.targetType())).map(TraceLink::sourceId).collect(Collectors.toSet());
        Set<String> executed = links.stream().filter(v -> "EXECUTION".equals(v.targetType())).map(TraceLink::sourceId).collect(Collectors.toSet());
        Set<String> runtimeCovered = links.stream().filter(v -> "COVERAGE".equals(v.targetType())).map(TraceLink::sourceId).collect(Collectors.toSet());
        Set<String> closed = new HashSet<>(tested); closed.retainAll(implemented);
        int total = criteria.size();
        long open = findings.stream().filter(v -> v.reviewStatus() == ReviewStatus.PENDING || v.reviewStatus() == ReviewStatus.CONFIRMED).count();
        return new Metrics(total, tested.size(), implemented.size(), executed.size(), runtimeCovered.size(),
                closed.size(), (int) open, rate(tested.size(), total), rate(implemented.size(), total),
                rate(executed.size(), total), rate(runtimeCovered.size(), total), rate(closed.size(), total));
    }

    private EvidenceLevel evidenceLevel(List<TestcaseProjection> tests, List<TraceLink> links) {
        if (links.stream().anyMatch(v -> v.evidenceLevel() == EvidenceLevel.E4)) return EvidenceLevel.E4;
        if (links.stream().anyMatch(v -> v.evidenceLevel() == EvidenceLevel.E3)) return EvidenceLevel.E3;
        if (links.stream().anyMatch(v -> v.evidenceLevel() == EvidenceLevel.E2)) return EvidenceLevel.E2;
        return tests.isEmpty() ? EvidenceLevel.E0 : EvidenceLevel.E1;
    }

    private boolean hasNumericConflict(AcceptanceCriterion ac, List<TestcaseProjection> tests) {
        Set<String> requirementNumbers = numbers(ac.content());
        if (requirementNumbers.isEmpty()) return false;
        return tests.stream().map(v -> numbers(v.expected())).filter(v -> !v.isEmpty())
                .anyMatch(v -> v.stream().noneMatch(requirementNumbers::contains));
    }

    private Set<String> numbers(String value) {
        Set<String> result = new HashSet<>(); Matcher matcher = NUMBER.matcher(value(value));
        while (matcher.find()) result.add(matcher.group()); return result;
    }

    private boolean hasSourceAssetEvidence(AcceptanceCriterion ac, String sourceAssetContent) {
        if (!StringUtils.hasText(sourceAssetContent)) return false;
        String acText = value(ac.title()) + " " + value(ac.content());
        String source = sourceAssetContent.toLowerCase(Locale.ROOT);
        Set<String> requirementNumbers = numbers(acText);
        if (!requirementNumbers.isEmpty() && requirementNumbers.stream().noneMatch(source::contains)) return false;

        int requiredConcepts = 0;
        int matchedConcepts = 0;
        if (containsAny(acText, "锁定", "禁用", "冻结")) {
            requiredConcepts++;
            if (containsAny(source, "lock", "locked", "disable", "frozen")) matchedConcepts++;
        }
        if (containsAny(acText, "解除", "解锁", "自动解除", "恢复")) {
            requiredConcepts++;
            if (containsAny(source, "unlock", "unlocked", "recover", "restore")) matchedConcepts++;
        }
        if (containsAny(acText, "密码", "错误", "失败")) {
            requiredConcepts++;
            if (containsAny(source, "password", "credential", "failed", "fail", "badcredentials")) matchedConcepts++;
        }
        if (containsAny(acText, "分钟", "小时", "秒")) {
            requiredConcepts++;
            if (containsAny(source, "duration", "ofminutes", "minute", "minutes", "timeout", "expire")) matchedConcepts++;
        }
        if (requiredConcepts > 0) return matchedConcepts == requiredConcepts;
        return similarity(ac.content(), sourceAssetContent) >= 0.05;
    }

    private double similarity(String left, String right) {
        Set<String> a = words(left); Set<String> b = words(right);
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> intersection = new HashSet<>(a); intersection.retainAll(b);
        Set<String> union = new HashSet<>(a); union.addAll(b);
        return (double) intersection.size() / union.size();
    }

    private Set<String> words(String value) {
        Set<String> result = new HashSet<>(); Matcher matcher = WORD.matcher(value(value).toLowerCase(Locale.ROOT));
        while (matcher.find()) result.add(matcher.group()); return result;
    }

    private AssetSnapshot requiredAsset(String projectId, String id, AssetType type) {
        AssetSnapshot asset = repository.findAsset(projectId, id).orElseThrow(() -> new IllegalArgumentException("找不到资产: " + id));
        Assert.isTrue(asset.assetType() == type, "资产类型不匹配: " + id);
        return asset;
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
    private String percent(double value) { return Math.round(value * 10000) / 100.0 + "%"; }
    private String value(String value) { return value == null ? "" : value; }
    private boolean containsIgnoreCase(String text, String needle) {
        return StringUtils.hasText(needle) && value(text).toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }
    private boolean containsAny(String text, String... needles) {
        String safe = value(text).toLowerCase(Locale.ROOT);
        for (String needle : needles) if (safe.contains(needle.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    public record CreateBaseline(String name, String requirementAssetId, String testcaseAssetId, String sourceAssetId,
                                 String executionAssetId, String coverageAssetId, String sourceAppId,
                                 String repositoryUrl, String sourceBranch, String sourceCommit) {}
    public record ReviewFinding(ReviewStatus status, String reason, String externalWorkItemUrl) {}
    public record ReviewTraceLink(ReviewStatus status) {}
    public record WriteBackFinding(String connectorType, String externalUrl, String message) {}
    public record GatePolicy(double minimumTestcaseCoverage, double minimumImplementationCoverage,
                             double minimumExecutionEvidence, double minimumRuntimeCoverage,
                             int maximumOpenHighFindings, boolean blocking) {
        public static GatePolicy defaults() { return new GatePolicy(1.0, 1.0, 0.0, 0.0, 0, false); }
    }
}
