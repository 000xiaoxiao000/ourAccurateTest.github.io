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
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

@Service
public class GitImpactAnalysisService {
    private static final Logger logger = LoggerFactory.getLogger(GitImpactAnalysisService.class);
    private static final int LLM_REVIEW_TIMEOUT_SECONDS = 8;
    private final GitService gitService;
    private final LanguageAnalyzer javaAnalyzer;
    private final StructuralDiffEngine structuralDiffEngine;
    private final ImpactPropagationEngine propagationEngine;
    private final LLMService llmService;
    private final Executor verificationAiExecutor;
    private final ConcurrentHashMap<String, LlmReviewProgress> llmReviews = new ConcurrentHashMap<>();

    public GitImpactAnalysisService(GitService gitService, JavaTreeSitterAnalyzer javaAnalyzer,
                                    StructuralDiffEngine structuralDiffEngine, ImpactPropagationEngine propagationEngine,
                                    LLMService llmService,
                                    @Qualifier("verificationAiExecutor") Executor verificationAiExecutor) {
        this.gitService = gitService;
        this.javaAnalyzer = javaAnalyzer;
        this.structuralDiffEngine = structuralDiffEngine;
        this.propagationEngine = propagationEngine;
        this.llmService = llmService;
        this.verificationAiExecutor = verificationAiExecutor;
    }

    public ImpactReport analyze(AppVo app, String baseCommit, String headCommit) {
        List<GitDiffVo> files = gitService.getDiffDetail(app.getRepoAddress(), app.getRepoUserName(), app.getRepoPassword(), baseCommit, headCommit);
        List<FileChange> fileChanges = new ArrayList<>();
        List<SymbolChange> changes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        for (GitDiffVo file : files) {
            String path = StringUtils.hasText(file.getNewPath()) && !"/dev/null".equals(file.getNewPath()) ? file.getNewPath() : file.getOldPath();
            fileChanges.add(new FileChange(file.getOldPath(), file.getNewPath(), FileChangeType.valueOf(file.getChangeType()), file.getRenameScore(), file.getOldBlobId(), file.getNewBlobId(), ranges(file.getOldRanges()), ranges(file.getNewRanges()), false, language(path)));
            if (!javaAnalyzer.supports(path)) continue;
            String oldSource = StringUtils.hasText(file.getOldPath()) && !"/dev/null".equals(file.getOldPath()) ? gitService.getFileContent(app.getRepoAddress(), app.getRepoUserName(), app.getRepoPassword(), baseCommit, file.getOldPath()) : null;
            String newSource = StringUtils.hasText(file.getNewPath()) && !"/dev/null".equals(file.getNewPath()) ? gitService.getFileContent(app.getRepoAddress(), app.getRepoUserName(), app.getRepoPassword(), headCommit, file.getNewPath()) : null;
            List<SymbolSnapshot> oldSymbols = javaAnalyzer.analyze(path, oldSource);
            List<SymbolSnapshot> newSymbols = javaAnalyzer.analyze(path, newSource);
            changes.addAll(structuralDiffEngine.diff(oldSymbols, newSymbols));
            for (SymbolSnapshot source : newSymbols) for (String invoked : source.invokedNames()) for (SymbolSnapshot target : newSymbols) if (target.qualifiedName().endsWith("." + invoked)) edges.add(new GraphEdge(source.key(), target.key(), EdgeType.CALLS, .8d, false, "Tree-sitter call-site candidate"));
        }
        ChangeSet changeSet = new ChangeSet(app.getRepoAddress(), baseCommit, headCommit, baseCommit, ImpactModels.ANALYZER_VERSION, LocalDateTime.now(), fileChanges);
        List<ImpactCandidate> candidates = propagationEngine.propagate(changes, edges, 3);
        String reportId = UUID.randomUUID().toString();
        scheduleLlmReview(reportId, candidates);
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
    private String language(String path) { return path != null && path.endsWith(".java") ? "java" : "unknown"; }

    public enum LlmReviewStatus { PENDING, RUNNING, COMPLETED, FAILED, UNAVAILABLE, NOT_FOUND }

    public record LlmReviewProgress(String reportId, LlmReviewStatus status, int total, int completed,
                                    List<LlmJudgement> judgements, String message) {
    }
}
