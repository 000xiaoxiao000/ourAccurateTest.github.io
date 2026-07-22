package com.oAT.web.verification.graph;

import com.oAT.web.verification.VerificationRepository;
import com.oAT.web.verification.model.GraphModels;
import com.oAT.web.verification.model.GraphModels.EvidenceKind;
import com.oAT.web.verification.model.GraphModels.GraphEdgeType;
import com.oAT.web.verification.model.GraphModels.GraphNodeKind;
import com.oAT.web.verification.model.GraphModels.SnapshotKind;
import com.oAT.web.verification.model.VerificationModels.TestcaseProjection;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Materializes TestExecution nodes and their EXECUTED_AS / TOUCHED edges from recorded runtime executions.
 * A TestExecution binds one recorded batch (execution id + commit + environment) to the TestCase that ran it
 * (EXECUTED_AS) and to the methods it actually touched via dynamic trace facts (TOUCHED). Only real recorded
 * executions and real dynamic call edges are used; nothing is inferred from static structure.
 */
@Service
public class TestExecutionProjectionService {
    public static final String ANALYZER_VERSION = "test-execution-v1";
    private final GraphRepository graphRepository;
    private final VerificationRepository verificationRepository;

    public TestExecutionProjectionService(GraphRepository graphRepository, VerificationRepository verificationRepository) {
        this.graphRepository = graphRepository;
        this.verificationRepository = verificationRepository;
    }

    public ProjectionResult project(String projectId, String baselineId) {
        verificationRepository.findBaseline(projectId, baselineId)
                .orElseThrow(() -> new IllegalArgumentException("分析基线不存在或不属于当前项目"));
        List<GraphRepository.RuntimeExecution> executions = graphRepository.findRuntimeExecutions(baselineId);
        List<TestcaseProjection> testcases = verificationRepository.findTestcases(baselineId);
        List<GraphRepository.GraphEdge> traceEdges = graphRepository.findActiveEdgesByType(baselineId, GraphEdgeType.CALLS_RUNTIME);
        List<GraphRepository.GraphNode> testcaseNodes = graphRepository.findActiveNodesByKind(baselineId, GraphNodeKind.TESTCASE);

        String inputHash = GraphModels.fingerprint(executions.stream().map(GraphRepository.RuntimeExecution::id).sorted().reduce("", (a, b) -> a + "|" + b));
        // Attach the execution overlay onto the existing trace snapshot so TestExecution nodes are versioned
        // together with the trace facts they depend on, without wiping those facts.
        GraphRepository.GraphSnapshot snapshot = graphRepository.findActiveSnapshot(baselineId, SnapshotKind.RUNTIME_TRACE)
                .orElseGet(() -> {
                    GraphRepository.GraphSnapshot created = new GraphRepository.GraphSnapshot(UUID.randomUUID().toString(), projectId, baselineId,
                            null, null, SnapshotKind.RUNTIME_TRACE, ANALYZER_VERSION, inputHash, "READY",
                            Map.of("executionCount", executions.size()));
                    graphRepository.saveSnapshot(created);
                    return created;
                });

        int nodes = 0;
        int edges = 0;
        for (GraphRepository.RuntimeExecution execution : executions) {
            String testcaseKey = metadata(execution.attributes(), "testcaseKey");
            String executionNodeId = "test-execution:" + GraphModels.fingerprint(baselineId + "|" + execution.id());
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("executionId", execution.id());
            attributes.put("externalExecutionId", execution.externalExecutionId());
            attributes.put("environment", execution.environment());
            attributes.put("sourceCommit", execution.sourceCommit());
            attributes.put("collectorVersion", execution.collectorVersion());
            attributes.put("testcaseKey", testcaseKey);
            graphRepository.saveNode(new GraphRepository.GraphNode(executionNodeId, snapshot.id(), baselineId, projectId,
                    GraphNodeKind.TEST_EXECUTION, execution.id(), execution.externalExecutionId(),
                    execution.environment(), "TestExecution " + execution.id().substring(0, Math.min(8, execution.id().length())), null, attributes));
            nodes++;

            String testcaseNodeId = resolveTestcaseNode(testcaseKey, testcases, testcaseNodes);
            if (testcaseNodeId != null) {
                graphRepository.saveEdge(edge(snapshot, projectId, baselineId, testcaseNodeId, executionNodeId, GraphEdgeType.EXECUTED_AS,
                        EvidenceKind.DYNAMIC_TRACE, "E4", 1d, execution.id(), Map.of("testcaseKey", testcaseKey)));
                edges++;
            }

            for (GraphRepository.GraphEdge trace : traceEdges) {
                if (!execution.id().equals(trace.executionId())) continue;
                graphRepository.saveEdge(edge(snapshot, projectId, baselineId, executionNodeId, trace.targetNodeId(), GraphEdgeType.TOUCHED,
                        EvidenceKind.DYNAMIC_TRACE, "E4", 1d, execution.id(), Map.of("via", "CALLS_RUNTIME")));
                edges++;
            }
        }
        return new ProjectionResult(snapshot.id(), nodes, edges);
    }

    private String resolveTestcaseNode(String testcaseKey, List<TestcaseProjection> testcases, List<GraphRepository.GraphNode> testcaseNodes) {
        if (!StringUtils.hasText(testcaseKey)) return null;
        for (GraphRepository.GraphNode node : testcaseNodes) {
            if (testcaseKey.equals(node.logicalSymbolId()) || testcaseKey.equalsIgnoreCase(node.displayName())
                    || (node.displayName() != null && node.displayName().startsWith(testcaseKey + " "))) {
                return node.id();
            }
        }
        return null;
    }

    private GraphRepository.GraphEdge edge(GraphRepository.GraphSnapshot snapshot, String projectId, String baselineId, String source,
                                           String target, GraphEdgeType type, EvidenceKind kind, String level, double confidence,
                                           String executionId, Map<String, Object> attributes) {
        return new GraphRepository.GraphEdge(GraphModels.edgeId(snapshot.id(), source, target, type, kind, executionId), snapshot.id(),
                baselineId, projectId, source, target, type, kind, level, confidence, executionId, null, attributes);
    }

    private String metadata(Map<String, Object> attributes, String key) {
        Object value = attributes == null ? null : attributes.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    public record ProjectionResult(String snapshotId, int nodeCount, int edgeCount) {}
}
