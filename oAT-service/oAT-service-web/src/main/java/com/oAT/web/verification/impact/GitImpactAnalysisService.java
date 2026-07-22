package com.oAT.web.verification.impact;

import com.fasterxml.jackson.databind.JsonNode;
import com.oAT.ai.service.LLMService;
import com.oAT.web.common.UtilJson;
import com.oAT.web.service.GitService;
import com.oAT.web.service.entity.AppVo;
import com.oAT.web.service.entity.GitDiffVo;
import com.oAT.web.verification.impact.ImpactModels.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.function.Consumer;

@Service
public class GitImpactAnalysisService {
    private static final Logger logger = LoggerFactory.getLogger(GitImpactAnalysisService.class);
    private static final int LLM_REVIEW_TIMEOUT_SECONDS = 8;
    private static final int MAX_STRUCTURAL_SOURCE_CHARS = 500_000;
    private final GitService gitService;
    private final List<LanguageAnalyzer> languageAnalyzers;
    private final StructuralDiffEngine structuralDiffEngine;
    private final ImpactPropagationEngine propagationEngine;
    private final LLMService llmService;
    private final Executor verificationAiExecutor;
    private final ConcurrentHashMap<String, LlmReviewProgress> llmReviews = new ConcurrentHashMap<>();

    public GitImpactAnalysisService(GitService gitService, List<LanguageAnalyzer> languageAnalyzers,
                                    StructuralDiffEngine structuralDiffEngine, ImpactPropagationEngine propagationEngine,
                                    LLMService llmService,
                                    @Qualifier("verificationAiExecutor") Executor verificationAiExecutor) {
        this.gitService = gitService;
        this.languageAnalyzers = List.copyOf(languageAnalyzers);
        this.structuralDiffEngine = structuralDiffEngine;
        this.propagationEngine = propagationEngine;
        this.llmService = llmService;
        this.verificationAiExecutor = verificationAiExecutor;
    }

    public ImpactReport analyze(AppVo app, String baseCommit, String headCommit) {
        return analyze(app, baseCommit, headCommit, ignored -> { });
    }

    public ImpactReport analyze(AppVo app, String baseCommit, String headCommit, Consumer<AnalysisProgress> progress) {
        Consumer<AnalysisProgress> reporter = progress == null ? ignored -> { } : progress;
        reporter.accept(new AnalysisProgress("PREPARING", 3, "准备 Git 影响分析"));
        reporter.accept(new AnalysisProgress("FETCHING_DIFF", 8, "正在读取两个 Commit 的文件差异"));
        List<GitDiffVo> files = gitService.getDiffDetail(app.getRepoAddress(), app.getRepoUserName(), app.getRepoPassword(), baseCommit, headCommit);
        reporter.accept(new AnalysisProgress("ANALYZING_FILES", 12, "发现 " + files.size() + " 个变更文件，开始结构化解析"));
        List<FileChange> fileChanges = new ArrayList<>();
        List<SymbolChange> changes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        reporter.accept(new AnalysisProgress("READING_CONTENT", 14, "正在读取变更文件内容"));
        Map<String, String> oldSources = gitService.getFileContents(app.getRepoAddress(), app.getRepoUserName(), app.getRepoPassword(), baseCommit, readablePaths(files, true));
        Map<String, String> newSources = gitService.getFileContents(app.getRepoAddress(), app.getRepoUserName(), app.getRepoPassword(), headCommit, readablePaths(files, false));
        int totalFiles = Math.max(1, files.size());
        int index = 0;
        for (GitDiffVo file : files) {
            index++;
            String path = StringUtils.hasText(file.getNewPath()) && !"/dev/null".equals(file.getNewPath()) ? file.getNewPath() : file.getOldPath();
            int percent = 12 + (int) Math.round(index * 58d / totalFiles);
            reporter.accept(new AnalysisProgress("ANALYZING_FILES", Math.min(70, percent), "正在解析 " + path + " (" + index + "/" + files.size() + ")"));
            fileChanges.add(new FileChange(file.getOldPath(), file.getNewPath(), FileChangeType.valueOf(file.getChangeType()), file.getRenameScore(), file.getOldBlobId(), file.getNewBlobId(), ranges(file.getOldRanges()), ranges(file.getNewRanges()), false, language(path)));
            LanguageAnalyzer analyzer = analyzerFor(path);
            if (analyzer == null) continue;
            String oldSource = oldSources.get(file.getOldPath());
            String newSource = newSources.get(file.getNewPath());
            if (tooLarge(oldSource) || tooLarge(newSource)) {
                logger.info("Skip structural analysis for large changed file {}", path);
                continue;
            }
            List<SymbolSnapshot> oldSymbols = analyzer.analyze(path, oldSource);
            List<SymbolSnapshot> newSymbols = analyzer.analyze(path, newSource);
            changes.addAll(structuralDiffEngine.diff(oldSymbols, newSymbols));
            edges.addAll(dependencyEdges(newSymbols));
        }
        reporter.accept(new AnalysisProgress("PROPAGATING", 78, "正在基于调用图传播影响范围"));
        ChangeSet changeSet = new ChangeSet(app.getRepoAddress(), baseCommit, headCommit, baseCommit, ImpactModels.ANALYZER_VERSION, LocalDateTime.now(), fileChanges);
        List<ImpactCandidate> candidates = propagationEngine.propagate(changes, edges, 3);
        String reportId = UUID.randomUUID().toString();
        reporter.accept(new AnalysisProgress("SCHEDULING_LLM", 88, "正在创建 LLM 辅助确认后台任务"));
        scheduleLlmReview(reportId, candidates);
        reporter.accept(new AnalysisProgress("COMPLETED", 95, "确定性影响分析完成"));
        return new ImpactReport(reportId, changeSet, changes, candidates, List.of(), LocalDateTime.now());
    }

    public LlmReviewProgress llmReview(String reportId) {
        return llmReviews.getOrDefault(reportId, new LlmReviewProgress(reportId, LlmReviewStatus.NOT_FOUND, 0, 0, List.of(), "LLM 审阅任务不存在或已过期"));
    }

    private void scheduleLlmReview(String reportId, List<ImpactCandidate> candidates) {
        List<ImpactCandidate> reviewTargets = candidates.stream()
                .filter(c -> c.classification() != ImpactClassification.DIRECT)
                .sorted((left, right) -> Double.compare(right.confidence(), left.confidence()))
                .toList();
        if (reviewTargets.isEmpty()) {
            llmReviews.put(reportId, new LlmReviewProgress(reportId, LlmReviewStatus.COMPLETED, 0, 0, List.of(), "没有需要 LLM 辅助确认的传播候选"));
            return;
        }
        if (!llmService.isAvailable()) {
            llmReviews.put(reportId, new LlmReviewProgress(reportId, LlmReviewStatus.UNAVAILABLE, reviewTargets.size(), 0, List.of(), "LLM 服务不可用，已跳过辅助确认"));
            return;
        }
        llmReviews.put(reportId, new LlmReviewProgress(reportId, LlmReviewStatus.PENDING, reviewTargets.size(), 0, List.of(), ""));
        CompletableFuture.runAsync(() -> runLlmReview(reportId, reviewTargets), verificationAiExecutor);
    }

    private void runLlmReview(String reportId, List<ImpactCandidate> candidates) {
        List<LlmJudgement> result = new ArrayList<>();
        llmReviews.put(reportId, new LlmReviewProgress(reportId, LlmReviewStatus.RUNNING, candidates.size(), 0, List.of(), ""));
        try {
            for (ImpactCandidate candidate : candidates) {
                LlmJudgement judgement = judge(candidate);
                result.add(judgement);
                llmReviews.put(reportId, new LlmReviewProgress(reportId, LlmReviewStatus.RUNNING, candidates.size(), result.size(), List.copyOf(result), ""));
            }
            llmReviews.put(reportId, new LlmReviewProgress(reportId, LlmReviewStatus.COMPLETED, candidates.size(), result.size(), List.copyOf(result), ""));
        } catch (RuntimeException exception) {
            logger.warn("LLM impact review task failed for report {}: {}", reportId, exception.getMessage());
            llmReviews.put(reportId, new LlmReviewProgress(reportId, LlmReviewStatus.FAILED, candidates.size(), result.size(), List.copyOf(result), exception.getMessage()));
        }
    }

    private LlmJudgement judge(ImpactCandidate candidate) {
            LlmDecision decision = LlmDecision.UNCERTAIN;
            double confidence = .25d;
            try {
                LlmDecision parsedDecision = requestLlmDecision(candidate);
                if (parsedDecision == LlmDecision.CONFIRM) {
                    decision = LlmDecision.CONFIRM;
                    confidence = .5d;
                } else if (parsedDecision == LlmDecision.REJECT) {
                    decision = LlmDecision.REJECT;
                    confidence = .5d;
                }
            } catch (RuntimeException exception) {
                logger.warn("LLM impact review unavailable for {} -> {}: {}", candidate.seedSymbol(), candidate.targetSymbol(), exception.getMessage());
                decision = LlmDecision.UNCERTAIN;
                confidence = .25d;
            }
            return new LlmJudgement(candidate.seedSymbol() + "->" + candidate.targetSymbol(), decision, confidence,
                    "LLM constrained verdict", "MEDIUM", List.of(), List.of());
    }

    private LlmDecision requestLlmDecision(ImpactCandidate candidate) {
        try {
            String answer = CompletableFuture.supplyAsync(() -> llmService.chat("""
                            Return one valid json object only, with exactly one field named "decision".
                            The json value must be one of: CONFIRM, REJECT, UNCERTAIN.
                            Do not invent evidence or symbols that are absent from the candidate.
                            """, """
                            Assess this candidate impact. Output json only.
                            candidate: %s -> %s
                            path: %s
                            """.formatted(candidate.seedSymbol(), candidate.targetSymbol(), candidate.path().symbols())))
                    .completeOnTimeout(null, LLM_REVIEW_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .join();
            if (!StringUtils.hasText(answer)) {
                logger.warn("LLM impact review timed out or returned empty for {} -> {}", candidate.seedSymbol(), candidate.targetSymbol());
                return LlmDecision.UNCERTAIN;
            }
            return parseDecision(answer);
        } catch (RuntimeException exception) {
            logger.warn("LLM impact review unavailable for {} -> {}: {}", candidate.seedSymbol(), candidate.targetSymbol(), exception.getMessage());
            return LlmDecision.UNCERTAIN;
        }
    }

    private LlmDecision parseDecision(String answer) {
        if (!StringUtils.hasText(answer)) return LlmDecision.UNCERTAIN;
        try {
            JsonNode root = UtilJson.getObjectMapper().readTree(answer);
            String value = root.path("decision").asText("");
            return LlmDecision.valueOf(value.trim().toUpperCase());
        } catch (Exception exception) {
            logger.warn("Unable to parse LLM impact decision json: {}", exception.getMessage());
            return LlmDecision.UNCERTAIN;
        }
    }

    private List<LineRange> ranges(List<GitDiffVo.LineRange> ranges) { return ranges == null ? List.of() : ranges.stream().map(r -> new LineRange(r.getStartLine(), r.getEndLine())).toList(); }
    private String language(String path) {
        if (path == null) return "unknown";
        String lower = path.toLowerCase();
        if (lower.endsWith(".java")) return "java";
        if (lower.matches(".*\\.(js|jsx|ts|tsx|vue)$")) return "frontend";
        if (lower.endsWith(".go")) return "go";
        if (lower.endsWith(".py")) return "python";
        if (lower.matches(".*\\.(c|cc|cpp|cxx|h|hpp)$")) return "cpp";
        return "unknown";
    }

    private LanguageAnalyzer analyzerFor(String path) {
        return languageAnalyzers.stream().filter(analyzer -> analyzer.supports(path)).findFirst().orElse(null);
    }

    private Collection<String> readablePaths(List<GitDiffVo> files, boolean oldSide) {
        return files.stream()
                .map(file -> oldSide ? file.getOldPath() : file.getNewPath())
                .filter(path -> StringUtils.hasText(path) && !"/dev/null".equals(path))
                .distinct()
                .toList();
    }

    private boolean tooLarge(String source) {
        return source != null && source.length() > MAX_STRUCTURAL_SOURCE_CHARS;
    }

    private List<GraphEdge> dependencyEdges(List<SymbolSnapshot> symbols) {
        // index by simple type name for resolution within the same file / change set
        Map<String, List<SymbolSnapshot>> bySimpleName = new LinkedHashMap<>();
        for (SymbolSnapshot symbol : symbols) {
            String qualifiedName = symbol.qualifiedName();
            if (!StringUtils.hasText(qualifiedName)) continue;
            String simpleName = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
            bySimpleName.computeIfAbsent(simpleName, ignored -> new ArrayList<>()).add(symbol);
        }
        List<GraphEdge> result = new ArrayList<>();
        for (SymbolSnapshot source : symbols) {
            // CALLS — method invocations
            for (String invoked : source.invokedNames()) {
                for (SymbolSnapshot target : bySimpleName.getOrDefault(invoked, List.of())) {
                    result.add(new GraphEdge(source.key(), target.key(), EdgeType.CALLS, .8d, false,
                            "Tree-sitter call-site candidate"));
                }
            }
            // EXTENDS / IMPLEMENTS — supertype declarations on TYPE symbols
            for (String superType : source.superTypes()) {
                for (SymbolSnapshot target : bySimpleName.getOrDefault(superType, List.of())) {
                    EdgeType edgeType = target.kind() == ImpactModels.SymbolKind.TYPE
                            && source.snippet() != null && source.snippet().contains("interface")
                            ? EdgeType.EXTENDS : EdgeType.EXTENDS;
                    // use IMPLEMENTS when the source is a class implementing an interface-like target
                    edgeType = source.superTypes().contains(superType) ? EdgeType.EXTENDS : EdgeType.IMPLEMENTS;
                    result.add(new GraphEdge(source.key(), target.key(), edgeType, .9d, false,
                            "supertype declaration"));
                }
            }
            // INJECTS — fields annotated with @Autowired / @Resource / @Inject
            for (String fieldType : source.injectAnnotatedFields()) {
                for (SymbolSnapshot target : bySimpleName.getOrDefault(fieldType, List.of())) {
                    result.add(new GraphEdge(source.key(), target.key(), EdgeType.INJECTS, .85d, false,
                            "injection-annotated field"));
                }
            }
            // READS / WRITES — non-injected field type references (conservative: mark as READS,
            // actual opcode-level WRITES come from bytecode; here we emit READS as a best effort)
            for (String fieldType : source.fieldTypes()) {
                if (source.injectAnnotatedFields().contains(fieldType)) continue; // already covered by INJECTS
                for (SymbolSnapshot target : bySimpleName.getOrDefault(fieldType, List.of())) {
                    result.add(new GraphEdge(source.key(), target.key(), EdgeType.READS, .55d, false,
                            "field type reference (static inference)"));
                }
            }
        }
        return result;
    }

    public enum LlmReviewStatus { PENDING, RUNNING, COMPLETED, FAILED, UNAVAILABLE, NOT_FOUND }

    public record LlmReviewProgress(String reportId, LlmReviewStatus status, int total, int completed,
                                    List<LlmJudgement> judgements, String message) {
    }

    public record AnalysisProgress(String stage, int percent, String message) {
    }
}
