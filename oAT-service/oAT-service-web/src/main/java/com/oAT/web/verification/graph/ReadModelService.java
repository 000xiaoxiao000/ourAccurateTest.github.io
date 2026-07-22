package com.oAT.web.verification.graph;

import com.oAT.web.verification.VerificationRepository;
import com.oAT.web.verification.model.GraphModels;
import com.oAT.web.verification.model.GraphModels.GraphEdgeType;
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
 * Precomputed read models (plan section 12). Rather than recursing on page open, high-frequency views are
 * materialized as graph aggregates and served from there. Each rebuild recomputes deterministically from
 * current active facts and aggregates, so read models never drift from the facts they summarize.
 */
@Service
public class ReadModelService {
    public static final String AC_COVERAGE_SUMMARY = "RM_AC_COVERAGE_SUMMARY";
    public static final String SYMBOL_TEST_PROTECTION = "RM_SYMBOL_TEST_PROTECTION";
    public static final String IMPACT_SUMMARY = "RM_IMPACT_SUMMARY";
    public static final String HOT_CALL_CHAIN = "RM_HOT_CALL_CHAIN";
    public static final String UNCOVERED_UNITS = "RM_UNCOVERED_UNITS";
    public static final String QUALITY_GATE_SUMMARY = "RM_QUALITY_GATE_SUMMARY";

    private final GraphRepository graphRepository;
    private final VerificationRepository verificationRepository;
    private final FusionViewService fusionViewService;

    public ReadModelService(GraphRepository graphRepository, VerificationRepository verificationRepository,
                            FusionViewService fusionViewService) {
        this.graphRepository = graphRepository;
        this.verificationRepository = verificationRepository;
        this.fusionViewService = fusionViewService;
    }

    public RebuildResult rebuildAll(String projectId, String baselineId) {
        verificationRepository.findBaseline(projectId, baselineId)
                .orElseThrow(() -> new IllegalArgumentException("分析基线不存在或不属于当前项目"));
        int acRows = rebuildAcCoverageSummary(projectId, baselineId);
        int protectionRows = rebuildSymbolTestProtection(projectId, baselineId);
        int hotRows = rebuildHotCallChain(projectId, baselineId);
        int uncoveredRows = rebuildUncoveredUnits(projectId, baselineId);
        int impactRows = rebuildImpactSummary(projectId, baselineId);
        int gateRows = rebuildQualityGateSummary(projectId, baselineId);
        return new RebuildResult(acRows, protectionRows, hotRows, uncoveredRows, impactRows, gateRows);
    }

    public List<GraphRepository.GraphAggregate> read(String projectId, String baselineId, String kind) {
        verificationRepository.findBaseline(projectId, baselineId)
                .orElseThrow(() -> new IllegalArgumentException("分析基线不存在或不属于当前项目"));
        return graphRepository.findActiveAggregates(baselineId, kind);
    }

    private int rebuildAcCoverageSummary(String projectId, String baselineId) {
        graphRepository.invalidateAggregates(baselineId, AC_COVERAGE_SUMMARY);
        List<AcceptanceCriterion> criteria = verificationRepository.findCriteria(baselineId);
        List<TraceLink> links = verificationRepository.findTraceLinks(baselineId);
        int rows = 0;
        for (AcceptanceCriterion criterion : criteria) {
            List<TraceLink> evidence = links.stream().filter(link -> criterion.id().equals(link.sourceId())).toList();
            boolean testcase = evidence.stream().anyMatch(link -> "TESTCASE".equals(link.targetType()));
            boolean implementation = evidence.stream().anyMatch(link -> "SOURCE_SYMBOL".equals(link.targetType()));
            boolean coverage = evidence.stream().anyMatch(link -> "COVERAGE".equals(link.targetType()));
            boolean execution = evidence.stream().anyMatch(link -> "EXECUTION".equals(link.targetType()));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("acKey", criterion.acKey());
            payload.put("requirementKey", criterion.requirementKey());
            payload.put("testcaseLinked", testcase);
            payload.put("implementationLinked", implementation);
            payload.put("coverageLinked", coverage);
            payload.put("executionLinked", execution);
            payload.put("evidenceLinkCount", evidence.size());
            save(projectId, baselineId, AC_COVERAGE_SUMMARY, criterion.id(), payload);
            rows++;
        }
        return rows;
    }

    private int rebuildSymbolTestProtection(String projectId, String baselineId) {
        graphRepository.invalidateAggregates(baselineId, SYMBOL_TEST_PROTECTION);
        List<TraceLink> links = verificationRepository.findTraceLinks(baselineId);
        Map<String, Set<String>> acsBySymbol = new LinkedHashMap<>();
        Map<String, Set<String>> testcasesByAc = new LinkedHashMap<>();
        for (TraceLink link : links) {
            if ("SOURCE_SYMBOL".equals(link.targetType())) {
                acsBySymbol.computeIfAbsent(link.targetId(), ignored -> new LinkedHashSet<>()).add(link.sourceId());
            } else if ("TESTCASE".equals(link.targetType())) {
                testcasesByAc.computeIfAbsent(link.sourceId(), ignored -> new LinkedHashSet<>()).add(link.targetId());
            }
        }
        int rows = 0;
        for (Map.Entry<String, Set<String>> entry : acsBySymbol.entrySet()) {
            Set<String> protectingTestcases = new LinkedHashSet<>();
            for (String acId : entry.getValue()) protectingTestcases.addAll(testcasesByAc.getOrDefault(acId, Set.of()));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("symbolId", entry.getKey());
            payload.put("acIds", new ArrayList<>(entry.getValue()));
            payload.put("testcaseIds", new ArrayList<>(protectingTestcases));
            payload.put("protected", !protectingTestcases.isEmpty());
            save(projectId, baselineId, SYMBOL_TEST_PROTECTION, GraphModels.fingerprint(entry.getKey()), payload);
            rows++;
        }
        return rows;
    }

    private int rebuildHotCallChain(String projectId, String baselineId) {
        graphRepository.invalidateAggregates(baselineId, HOT_CALL_CHAIN);
        List<GraphRepository.GraphEdge> runtimeCalls = graphRepository.findActiveEdgesByType(baselineId, GraphEdgeType.CALLS_RUNTIME);
        int rows = 0;
        for (GraphRepository.GraphEdge edge : runtimeCalls) {
            Map<String, Object> attributes = edge.attributes() == null ? Map.of() : edge.attributes();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sourceNodeId", edge.sourceNodeId());
            payload.put("targetNodeId", edge.targetNodeId());
            payload.put("count", attributes.getOrDefault("count", 0));
            payload.put("durationMs", attributes.getOrDefault("durationMs", 0));
            payload.put("outcome", attributes.getOrDefault("outcome", ""));
            payload.put("traceId", attributes.getOrDefault("traceId", ""));
            save(projectId, baselineId, HOT_CALL_CHAIN, GraphModels.fingerprint(edge.sourceNodeId() + "->" + edge.targetNodeId()), payload);
            rows++;
        }
        return rows;
    }

    private int rebuildUncoveredUnits(String projectId, String baselineId) {
        graphRepository.invalidateAggregates(baselineId, UNCOVERED_UNITS);
        FusionViewService.FusionView fusion = fusionViewService.build(projectId, baselineId, 5_000);
        int rows = 0;
        for (FusionViewService.FusionNode node : fusion.nodes()) {
            if (!"REACHABLE_NOT_EXECUTED".equals(node.fusionState())) continue;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("nodeId", node.nodeId());
            payload.put("symbolId", node.stableSymbolId());
            payload.put("displayName", node.displayName());
            payload.put("locator", node.locator());
            payload.put("reason", "静态可达但本基线无动态执行证据");
            save(projectId, baselineId, UNCOVERED_UNITS, GraphModels.fingerprint(node.nodeId()), payload);
            rows++;
        }
        return rows;
    }

    private int rebuildImpactSummary(String projectId, String baselineId) {
        graphRepository.invalidateAggregates(baselineId, IMPACT_SUMMARY);
        List<TraceLink> links = verificationRepository.findTraceLinks(baselineId);
        // For each implementation symbol, summarize which ACs and testcases it protects/affects.
        Map<String, Set<String>> acsBySymbol = new LinkedHashMap<>();
        Map<String, Set<String>> testcasesByAc = new LinkedHashMap<>();
        for (TraceLink link : links) {
            if ("SOURCE_SYMBOL".equals(link.targetType())) {
                acsBySymbol.computeIfAbsent(link.targetId(), ignored -> new LinkedHashSet<>()).add(link.sourceId());
            } else if ("TESTCASE".equals(link.targetType())) {
                testcasesByAc.computeIfAbsent(link.sourceId(), ignored -> new LinkedHashSet<>()).add(link.targetId());
            }
        }
        int rows = 0;
        for (Map.Entry<String, Set<String>> entry : acsBySymbol.entrySet()) {
            Set<String> affectedAcs = entry.getValue();
            Set<String> affectedTestcases = new LinkedHashSet<>();
            for (String acId : affectedAcs) affectedTestcases.addAll(testcasesByAc.getOrDefault(acId, Set.of()));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("changedSymbol", entry.getKey());
            payload.put("affectedAcIds", new ArrayList<>(affectedAcs));
            payload.put("affectedTestcaseIds", new ArrayList<>(affectedTestcases));
            payload.put("affectedAcCount", affectedAcs.size());
            payload.put("affectedTestcaseCount", affectedTestcases.size());
            save(projectId, baselineId, IMPACT_SUMMARY, GraphModels.fingerprint(entry.getKey()), payload);
            rows++;
        }
        return rows;
    }

    private int rebuildQualityGateSummary(String projectId, String baselineId) {
        graphRepository.invalidateAggregates(baselineId, QUALITY_GATE_SUMMARY);
        List<AcceptanceCriterion> criteria = verificationRepository.findCriteria(baselineId);
        List<TraceLink> links = verificationRepository.findTraceLinks(baselineId);
        Set<String> withTestcase = new LinkedHashSet<>();
        Set<String> withImplementation = new LinkedHashSet<>();
        Set<String> withCoverage = new LinkedHashSet<>();
        Set<String> withExecution = new LinkedHashSet<>();
        for (TraceLink link : links) {
            switch (link.targetType()) {
                case "TESTCASE" -> withTestcase.add(link.sourceId());
                case "SOURCE_SYMBOL" -> withImplementation.add(link.sourceId());
                case "COVERAGE" -> withCoverage.add(link.sourceId());
                case "EXECUTION" -> withExecution.add(link.sourceId());
                default -> { }
            }
        }
        int total = criteria.size();
        Set<String> closedLoop = new LinkedHashSet<>(withTestcase);
        closedLoop.retainAll(withImplementation);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("totalCriteria", total);
        payload.put("testcaseCoveredCount", withTestcase.size());
        payload.put("implementationCoveredCount", withImplementation.size());
        payload.put("coverageCoveredCount", withCoverage.size());
        payload.put("executionCoveredCount", withExecution.size());
        payload.put("closedLoopCount", closedLoop.size());
        payload.put("testcaseCoverageRate", rate(withTestcase.size(), total));
        payload.put("implementationCoverageRate", rate(withImplementation.size(), total));
        payload.put("closedLoopRate", rate(closedLoop.size(), total));
        save(projectId, baselineId, QUALITY_GATE_SUMMARY, baselineId, payload);
        return 1;
    }

    private double rate(int value, int total) {
        return total == 0 ? 0 : Math.round((double) value / total * 10_000d) / 10_000d;
    }

    private void save(String projectId, String baselineId, String kind, String subjectId, Map<String, Object> payload) {
        String sourceHash = GraphModels.fingerprint(kind + "|" + subjectId + "|" + payload.hashCode());
        graphRepository.saveAggregate(new GraphRepository.GraphAggregate(
                kind.toLowerCase() + ":" + GraphModels.fingerprint(baselineId + "|" + subjectId), baselineId, projectId,
                kind, subjectId, sourceHash, payload));
    }

    public record RebuildResult(int acCoverageRows, int symbolProtectionRows, int hotCallChainRows,
                                int uncoveredUnitRows, int impactSummaryRows, int qualityGateSummaryRows) {}
}
