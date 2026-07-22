package com.oAT.web.verification.graph;

import com.oAT.web.verification.VerificationRepository;
import com.oAT.web.verification.model.GraphModels;
import com.oAT.web.verification.model.GraphModels.EvidenceKind;
import com.oAT.web.verification.model.GraphModels.GraphEdgeType;
import com.oAT.web.verification.model.GraphModels.GraphNodeKind;
import com.oAT.web.verification.model.GraphModels.SnapshotKind;
import com.oAT.web.verification.model.VerificationModels.AcceptanceCriterion;
import com.oAT.web.verification.model.VerificationModels.TestcaseProjection;
import com.oAT.web.verification.model.VerificationModels.TraceLink;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TraceabilityGraphProjectionService {
    private final GraphRepository graphRepository;
    private final VerificationRepository verificationRepository;

    public TraceabilityGraphProjectionService(GraphRepository graphRepository, VerificationRepository verificationRepository) {
        this.graphRepository = graphRepository;
        this.verificationRepository = verificationRepository;
    }

    public ProjectionResult project(String projectId, String baselineId) {
        List<AcceptanceCriterion> criteria = verificationRepository.findCriteria(baselineId);
        List<TestcaseProjection> testcases = verificationRepository.findTestcases(baselineId);
        List<TraceLink> links = verificationRepository.findTraceLinks(baselineId);
        String inputHash = GraphModels.fingerprint(criteria.size() + "|" + testcases.size() + "|" + links.stream().map(TraceLink::id).sorted().reduce("", String::concat));
        graphRepository.invalidateSnapshots(baselineId, SnapshotKind.TRACEABILITY);
        GraphRepository.GraphSnapshot snapshot = new GraphRepository.GraphSnapshot(UUID.randomUUID().toString(), projectId, baselineId,
                null, null, SnapshotKind.TRACEABILITY, "traceability-projection-v1", inputHash, "READY",
                Map.of("criteria", criteria.size(), "testcases", testcases.size(), "links", links.size()));
        graphRepository.saveSnapshot(snapshot);
        Map<String, String> nodeIds = new LinkedHashMap<>();
        int nodes = 0;
        for (AcceptanceCriterion criterion : criteria) {
            String id = "ac:" + GraphModels.fingerprint(baselineId + "|" + criterion.id());
            nodeIds.put("AC:" + criterion.id(), id);
            graphRepository.saveNode(new GraphRepository.GraphNode(id, snapshot.id(), baselineId, projectId,
                    GraphNodeKind.ACCEPTANCE_CRITERION, criterion.id(), criterion.acKey(), criterion.sourceLocator(),
                    criterion.acKey() + " " + criterion.title(), GraphModels.fingerprint(criterion.content()),
                    Map.of("requirementKey", criterion.requirementKey(), "testable", criterion.testable(), "ambiguity", criterion.ambiguity())));
            nodes++;
        }
        for (TestcaseProjection testcase : testcases) {
            String id = "tc:" + GraphModels.fingerprint(baselineId + "|" + testcase.id());
            nodeIds.put("TESTCASE:" + testcase.id(), id);
            graphRepository.saveNode(new GraphRepository.GraphNode(id, snapshot.id(), baselineId, projectId,
                    GraphNodeKind.TESTCASE, testcase.id(), testcase.externalKey(), testcase.sourceLocator(), testcase.externalKey() + " " + testcase.title(),
                    GraphModels.fingerprint(testcase.steps() + "|" + testcase.expected()), Map.of("requirementRefs", testcase.requirementRefs())));
            nodes++;
        }
        int edges = 0;
        for (TraceLink link : links) {
            String source = nodeIds.get(link.sourceType() + ":" + link.sourceId());
            if (source == null && "AC".equals(link.sourceType())) source = nodeIds.get("AC:" + link.sourceId());
            if (source == null) continue;
            String target = nodeIds.get(link.targetType() + ":" + link.targetId());
            if (target == null && "SOURCE_SYMBOL".equals(link.targetType())) {
                target = graphRepository.findActiveNodeBySymbol(baselineId, link.targetId()).map(GraphRepository.GraphNode::id).orElse(null);
            }
            if (target == null) {
                target = createExternalTarget(snapshot, projectId, baselineId, link, nodeIds);
                nodes++;
            }
            GraphEdgeType type = edgeType(link);
            EvidenceKind evidence = evidenceKind(link);
            graphRepository.saveEdge(new GraphRepository.GraphEdge(GraphModels.edgeId(snapshot.id(), source, target, type, evidence, null),
                    snapshot.id(), baselineId, projectId, source, target, type, evidence, link.evidenceLevel().name(), link.confidence(), null,
                    stringValue(link.evidence().get("locator")), link.evidence()));
            edges++;
        }
        return new ProjectionResult(snapshot.id(), nodes, edges);
    }

    private String createExternalTarget(GraphRepository.GraphSnapshot snapshot, String projectId, String baselineId, TraceLink link,
                                        Map<String, String> nodeIds) {
        String key = link.targetType() + ":" + link.targetId();
        String existing = nodeIds.get(key);
        if (existing != null) return existing;
        GraphNodeKind kind = switch (link.targetType()) {
            case "SOURCE_SYMBOL" -> GraphNodeKind.METHOD;
            case "EXECUTION" -> GraphNodeKind.TEST_EXECUTION;
            case "COVERAGE" -> GraphNodeKind.COVERAGE_UNIT;
            default -> GraphNodeKind.RUNTIME_SPAN;
        };
        String id = "trace-target:" + GraphModels.fingerprint(baselineId + "|" + key);
        graphRepository.saveNode(new GraphRepository.GraphNode(id, snapshot.id(), baselineId, projectId, kind,
                link.targetId(), link.targetId(), stringValue(link.evidence().get("locator")), link.targetId(), null,
                Map.of("targetType", link.targetType(), "generationMethod", link.generationMethod())));
        nodeIds.put(key, id);
        return id;
    }

    private GraphEdgeType edgeType(TraceLink link) {
        return switch (link.relationType()) {
            case "IMPLEMENTED_BY" -> GraphEdgeType.IMPLEMENTED_BY;
            case "PROVEN_BY" -> GraphEdgeType.TOUCHED;
            case "COVERED_BY" -> GraphEdgeType.COVERED;
            case "AFFECTED_BY" -> GraphEdgeType.CONSUMES;
            default -> GraphEdgeType.VERIFIED_BY;
        };
    }

    private EvidenceKind evidenceKind(TraceLink link) {
        String method = (link.generationMethod() + " " + link.targetType()).toUpperCase();
        if (method.contains("COVERAGE")) return EvidenceKind.COVERAGE;
        if (method.contains("EXECUTION")) return EvidenceKind.DYNAMIC_TRACE;
        if (method.contains("STATIC") || "SOURCE_SYMBOL".equals(link.targetType())) return EvidenceKind.STATIC_POSSIBLE;
        if (method.contains("DOCUMENT")) return EvidenceKind.DOCUMENT_LINK;
        return EvidenceKind.AI_CANDIDATE;
    }

    private String stringValue(Object value) { return value == null ? null : String.valueOf(value); }
    public record ProjectionResult(String snapshotId, int nodeCount, int edgeCount) {}
}
