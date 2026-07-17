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
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class GitImpactAnalysisService {
    private static final Logger logger = LoggerFactory.getLogger(GitImpactAnalysisService.class);
    private final GitService gitService;
    private final LanguageAnalyzer javaAnalyzer;
    private final StructuralDiffEngine structuralDiffEngine;
    private final ImpactPropagationEngine propagationEngine;
    private final LLMService llmService;

    public GitImpactAnalysisService(GitService gitService, JavaTreeSitterAnalyzer javaAnalyzer,
                                    StructuralDiffEngine structuralDiffEngine, ImpactPropagationEngine propagationEngine,
                                    LLMService llmService) {
        this.gitService = gitService;
        this.javaAnalyzer = javaAnalyzer;
        this.structuralDiffEngine = structuralDiffEngine;
        this.propagationEngine = propagationEngine;
        this.llmService = llmService;
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
        return new ImpactReport(UUID.randomUUID().toString(), changeSet, changes, candidates, judge(candidates), LocalDateTime.now());
    }

    private List<LlmJudgement> judge(List<ImpactCandidate> candidates) {
        if (!llmService.isAvailable() || candidates.isEmpty()) return List.of();
        List<LlmJudgement> result = new ArrayList<>();
        for (ImpactCandidate candidate : candidates.stream().filter(c -> c.classification() != ImpactClassification.DIRECT).limit(20).toList()) {
            LlmDecision decision = LlmDecision.UNCERTAIN;
            double confidence = .25d;
            try {
                String answer = llmService.chat("""
                        Return one valid json object only, with exactly one field named "decision".
                        The json value must be one of: CONFIRM, REJECT, UNCERTAIN.
                        Do not invent evidence or symbols that are absent from the candidate.
                        """, """
                        Assess this candidate impact. Output json only.
                        candidate: %s -> %s
                        path: %s
                        """.formatted(candidate.seedSymbol(), candidate.targetSymbol(), candidate.path().symbols()));
                LlmDecision parsedDecision = parseDecision(answer);
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
            result.add(new LlmJudgement(candidate.seedSymbol() + "->" + candidate.targetSymbol(), decision, confidence,
                    "LLM constrained verdict", "MEDIUM", List.of(), List.of()));
        }
        return result;
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
}
