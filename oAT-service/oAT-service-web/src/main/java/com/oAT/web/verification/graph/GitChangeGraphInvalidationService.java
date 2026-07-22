package com.oAT.web.verification.graph;

import com.oAT.web.verification.VerificationRepository;
import com.oAT.web.verification.impact.ImpactModels;
import com.oAT.web.verification.impact.ImpactTraceabilityMapper;
import com.oAT.web.verification.model.GraphModels;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class GitChangeGraphInvalidationService {
    public static final String AGGREGATE_KIND = "GIT_CHANGE_IMPACT";
    private final GraphRepository graphRepository;
    private final VerificationRepository verificationRepository;

    public GitChangeGraphInvalidationService(GraphRepository graphRepository, VerificationRepository verificationRepository) {
        this.graphRepository = graphRepository;
        this.verificationRepository = verificationRepository;
    }

    public InvalidationResult apply(String projectId, String baselineId, ImpactModels.ImpactReport report,
                                    ImpactTraceabilityMapper.TraceabilityImpact traceability) {
        verificationRepository.findBaseline(projectId, baselineId)
                .orElseThrow(() -> new IllegalArgumentException("分析基线不存在或不属于当前项目"));
        List<String> changedSymbols = report.directChanges().stream().map(ImpactModels.SymbolChange::symbolKey).distinct().toList();
        List<String> affectedSymbols = traceability.affectedSymbols();
        List<String> affectedAcIds = traceability.affectedCriteria().stream().map(item -> item.id()).toList();
        List<String> affectedTestcaseIds = traceability.affectedTestcases().stream().map(item -> item.id()).toList();
        String sourceHash = GraphModels.fingerprint(report.changeSet().baseCommit() + "|" + report.changeSet().headCommit()
                + "|" + String.join("|", changedSymbols) + "|" + String.join("|", affectedAcIds));
        Map<String, Object> payload = Map.of(
                "reportId", report.id(),
                "baseCommit", report.changeSet().baseCommit(),
                "headCommit", report.changeSet().headCommit(),
                "changedFiles", report.changeSet().files().size(),
                "changedSymbols", changedSymbols,
                "affectedSymbols", affectedSymbols,
                "affectedAcIds", affectedAcIds,
                "affectedTestcaseIds", affectedTestcaseIds,
                "candidateCount", report.candidates().size());
        graphRepository.invalidateAll(baselineId);
        verificationRepository.markBaselineStale(baselineId);
        graphRepository.saveAggregate(new GraphRepository.GraphAggregate(
                "git-impact:" + GraphModels.fingerprint(baselineId + "|" + sourceHash), baselineId, projectId,
                AGGREGATE_KIND, report.id(), sourceHash, payload));
        return new InvalidationResult(report.id(), changedSymbols, affectedSymbols, affectedAcIds, affectedTestcaseIds);
    }

    public List<GraphRepository.GraphAggregate> history(String projectId, String baselineId) {
        verificationRepository.findBaseline(projectId, baselineId)
                .orElseThrow(() -> new IllegalArgumentException("分析基线不存在或不属于当前项目"));
        return graphRepository.findAggregates(baselineId, AGGREGATE_KIND);
    }

    public record InvalidationResult(String reportId, List<String> changedSymbols, List<String> affectedSymbols,
                                     List<String> affectedAcIds, List<String> affectedTestcaseIds) {}
}
