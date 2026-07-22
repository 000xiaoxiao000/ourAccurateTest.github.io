package com.oAT.web.verification.graph;

import com.oAT.web.verification.VerificationRepository;
import com.oAT.web.verification.model.GraphModels;
import com.oAT.web.verification.model.VerificationModels.AcceptanceCriterion;
import com.oAT.web.verification.model.VerificationModels.TraceLink;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * New-vs-old baseline comparison (plan section 5, "新旧基线比较"). Given a predecessor and a successor baseline,
 * computes deterministic diffs across two dimensions: (1) AC evidence closure — which ACs gained or lost
 * testcase/implementation/coverage/execution evidence; (2) fusion tri-state — which methods moved between
 * EXECUTED_CONFIRMED / REACHABLE_NOT_EXECUTED / NOT_OBSERVABLE. The result is stored as an aggregate on the
 * successor baseline so the comparison itself is versioned and reproducible.
 */
@Service
public class BaselineComparisonService {
    public static final String AGGREGATE_KIND = "BASELINE_COMPARISON";
    private final GraphRepository graphRepository;
    private final VerificationRepository verificationRepository;
    private final FusionViewService fusionViewService;

    public BaselineComparisonService(GraphRepository graphRepository, VerificationRepository verificationRepository,
                                     FusionViewService fusionViewService) {
        this.graphRepository = graphRepository;
        this.verificationRepository = verificationRepository;
        this.fusionViewService = fusionViewService;
    }

    public ComparisonResult compare(String projectId, String baseBaselineId, String targetBaselineId) {
        verificationRepository.findBaseline(projectId, baseBaselineId)
                .orElseThrow(() -> new IllegalArgumentException("基准分析基线不存在或不属于当前项目"));
        verificationRepository.findBaseline(projectId, targetBaselineId)
                .orElseThrow(() -> new IllegalArgumentException("目标分析基线不存在或不属于当前项目"));
        if (baseBaselineId.equals(targetBaselineId)) {
            throw new IllegalArgumentException("基准基线与目标基线不能相同");
        }

        Map<String, AcEvidence> baseAc = acEvidence(baseBaselineId);
        Map<String, AcEvidence> targetAc = acEvidence(targetBaselineId);
        List<AcDelta> acDeltas = diffAc(baseAc, targetAc);

        Map<String, String> baseFusion = fusionStateBySymbol(projectId, baseBaselineId);
        Map<String, String> targetFusion = fusionStateBySymbol(projectId, targetBaselineId);
        List<FusionDelta> fusionDeltas = diffFusion(baseFusion, targetFusion);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("baseBaselineId", baseBaselineId);
        payload.put("targetBaselineId", targetBaselineId);
        payload.put("acChangedCount", acDeltas.size());
        payload.put("fusionChangedCount", fusionDeltas.size());
        String sourceHash = GraphModels.fingerprint(baseBaselineId + "->" + targetBaselineId + "|" + acDeltas.size() + "|" + fusionDeltas.size());
        graphRepository.saveAggregate(new GraphRepository.GraphAggregate(
                "comparison:" + GraphModels.fingerprint(targetBaselineId + "|" + sourceHash), targetBaselineId, projectId,
                AGGREGATE_KIND, baseBaselineId, sourceHash, payload));

        return new ComparisonResult(baseBaselineId, targetBaselineId, acDeltas, fusionDeltas);
    }

    private Map<String, AcEvidence> acEvidence(String baselineId) {
        List<AcceptanceCriterion> criteria = verificationRepository.findCriteria(baselineId);
        List<TraceLink> links = verificationRepository.findTraceLinks(baselineId);
        Map<String, AcEvidence> result = new LinkedHashMap<>();
        for (AcceptanceCriterion criterion : criteria) {
            result.put(criterion.id(), new AcEvidence(criterion.acKey(), false, false, false, false));
        }
        for (TraceLink link : links) {
            AcEvidence current = result.get(link.sourceId());
            if (current == null) continue;
            switch (link.targetType()) {
                case "TESTCASE" -> result.put(link.sourceId(), current.withTestcase());
                case "SOURCE_SYMBOL" -> result.put(link.sourceId(), current.withImplementation());
                case "COVERAGE" -> result.put(link.sourceId(), current.withCoverage());
                case "EXECUTION" -> result.put(link.sourceId(), current.withExecution());
                default -> { }
            }
        }
        return result;
    }

    private List<AcDelta> diffAc(Map<String, AcEvidence> base, Map<String, AcEvidence> target) {
        List<AcDelta> deltas = new ArrayList<>();
        Set<String> allIds = new LinkedHashSet<>();
        allIds.addAll(base.keySet());
        allIds.addAll(target.keySet());
        for (String id : allIds) {
            AcEvidence b = base.get(id);
            AcEvidence t = target.get(id);
            if (b == null && t != null) {
                deltas.add(new AcDelta(id, t.acKey(), "ADDED", t.summary(), t.summary()));
            } else if (b != null && t == null) {
                deltas.add(new AcDelta(id, b.acKey(), "REMOVED", b.summary(), b.summary()));
            } else if (b != null && !b.equals(t)) {
                deltas.add(new AcDelta(id, t.acKey(), "EVIDENCE_CHANGED", b.summary(), t.summary()));
            }
        }
        return deltas;
    }

    private Map<String, String> fusionStateBySymbol(String projectId, String baselineId) {
        FusionViewService.FusionView view = fusionViewService.build(projectId, baselineId, 5_000);
        Map<String, String> result = new LinkedHashMap<>();
        for (FusionViewService.FusionNode node : view.nodes()) {
            String key = node.stableSymbolId() != null ? node.stableSymbolId() : node.displayName();
            result.put(key, node.fusionState());
        }
        return result;
    }

    private List<FusionDelta> diffFusion(Map<String, String> base, Map<String, String> target) {
        List<FusionDelta> deltas = new ArrayList<>();
        Set<String> allKeys = new LinkedHashSet<>();
        allKeys.addAll(base.keySet());
        allKeys.addAll(target.keySet());
        for (String key : allKeys) {
            String b = base.get(key);
            String t = target.get(key);
            if (b == null && t != null) deltas.add(new FusionDelta(key, "ABSENT", t));
            else if (b != null && t == null) deltas.add(new FusionDelta(key, b, "ABSENT"));
            else if (b != null && !b.equals(t)) deltas.add(new FusionDelta(key, b, t));
        }
        return deltas;
    }

    private record AcEvidence(String acKey, boolean testcase, boolean implementation, boolean coverage, boolean execution) {
        AcEvidence withTestcase() { return new AcEvidence(acKey, true, implementation, coverage, execution); }
        AcEvidence withImplementation() { return new AcEvidence(acKey, testcase, true, coverage, execution); }
        AcEvidence withCoverage() { return new AcEvidence(acKey, testcase, implementation, true, execution); }
        AcEvidence withExecution() { return new AcEvidence(acKey, testcase, implementation, coverage, true); }
        String summary() {
            return (testcase ? "T" : "-") + (implementation ? "I" : "-") + (coverage ? "C" : "-") + (execution ? "E" : "-");
        }
    }

    public record AcDelta(String criterionId, String acKey, String changeType, String beforeEvidence, String afterEvidence) {}
    public record FusionDelta(String symbol, String beforeState, String afterState) {}
    public record ComparisonResult(String baseBaselineId, String targetBaselineId,
                                   List<AcDelta> acDeltas, List<FusionDelta> fusionDeltas) {}
}
