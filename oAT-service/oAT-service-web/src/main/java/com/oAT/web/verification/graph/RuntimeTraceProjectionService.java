package com.oAT.web.verification.graph;

import com.oAT.web.verification.VerificationRepository;
import com.oAT.web.verification.model.GraphModels;
import com.oAT.web.verification.model.GraphModels.EvidenceKind;
import com.oAT.web.verification.model.GraphModels.GraphEdgeType;
import com.oAT.web.verification.model.GraphModels.GraphNodeKind;
import com.oAT.web.verification.model.GraphModels.SnapshotKind;
import com.oAT.web.verification.model.VerificationModels.Baseline;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class RuntimeTraceProjectionService {
    private static final String COLLECTOR_VERSION = "runtime-trace-json-v1";
    private final GraphRepository graphRepository;
    private final VerificationRepository verificationRepository;

    public RuntimeTraceProjectionService(GraphRepository graphRepository,
                                         VerificationRepository verificationRepository) {
        this.graphRepository = graphRepository;
        this.verificationRepository = verificationRepository;
    }

    public ProjectionResult project(String projectId, String baselineId, RuntimeTraceBatch batch) {
        Baseline baseline = verificationRepository.findBaseline(projectId, baselineId)
                .orElseThrow(() -> new IllegalArgumentException("分析基线不存在或不属于当前项目"));
        validate(baseline, batch);
        String inputHash = GraphModels.fingerprint(batch.executionId() + "|" + batch.traceHash() + "|" + batch.sourceCommit());
        graphRepository.invalidateSnapshots(baselineId, SnapshotKind.RUNTIME_TRACE);
        GraphRepository.GraphSnapshot snapshot = new GraphRepository.GraphSnapshot(UUID.randomUUID().toString(), projectId, baselineId,
                baseline.repositoryUrl(), batch.sourceCommit(), SnapshotKind.RUNTIME_TRACE, COLLECTOR_VERSION, inputHash, "READY",
                Map.of("executionId", batch.executionId(), "environment", batch.environment(), "spanCount", batch.calls().size()));
        graphRepository.saveSnapshot(snapshot);

        // Persist the D2 audit row — binds this batch to testcase, commit and environment.
        String executionId = UUID.randomUUID().toString();
        graphRepository.saveRuntimeExecution(new GraphRepository.RuntimeExecution(executionId, projectId, baselineId, batch.sourceCommit(),
                batch.executionId(), batch.environment(), batch.collectorVersion(), batch.traceHash(), null, batch.startedAt(), batch.finishedAt(),
                OffsetDateTime.now(), Map.of("testcaseKey", value(batch.testcaseKey()))));
        verificationRepository.saveRuntimeTestExecution(new com.oAT.web.verification.VerificationRepository.RuntimeTestExecution(
                executionId, projectId, baselineId, batch.sourceCommit(), batch.executionId(),
                batch.testcaseKey(), batch.environment(), batch.collectorVersion(),
                batch.traceHash(), inputHash, batch.startedAt(), batch.finishedAt(),
                OffsetDateTime.now(), Map.of("snapshotId", snapshot.id())));

        // Materialise a TEST_EXECUTION graph node so the execution is a first-class graph citizen.
        String testExecNodeId = "test-exec:" + GraphModels.fingerprint(executionId);
        graphRepository.saveNode(new GraphRepository.GraphNode(testExecNodeId, snapshot.id(), baselineId, projectId,
                GraphNodeKind.TEST_EXECUTION, executionId, executionId,
                batch.environment() + "/" + batch.executionId(),
                value(batch.testcaseKey()) + "@" + batch.sourceCommit().substring(0, Math.min(8, batch.sourceCommit().length())),
                GraphModels.fingerprint(inputHash),
                Map.of("executionId", batch.executionId(), "testcaseKey", value(batch.testcaseKey()),
                        "environment", batch.environment(), "commit", batch.sourceCommit())));

        int nodes = 1; // TEST_EXECUTION node
        int edges = 0;
        java.util.Set<String> touchedNodeIds = new java.util.LinkedHashSet<>();

        for (RuntimeCall call : batch.calls()) {
            validateCall(call);
            String callerId = runtimeNode(snapshot, projectId, baselineId, call.callerSymbolId(), call.callerLocator(), "caller");
            String calleeId = runtimeNode(snapshot, projectId, baselineId, call.calleeSymbolId(), call.calleeLocator(), "callee");
            nodes += 2;
            touchedNodeIds.add(callerId);
            touchedNodeIds.add(calleeId);

            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("traceId", call.traceId());
            attributes.put("spanId", call.spanId());
            attributes.put("parentSpanId", value(call.parentSpanId()));
            attributes.put("count", call.count());
            attributes.put("durationMs", call.durationMs());
            attributes.put("outcome", value(call.outcome()));
            attributes.put("testcaseKey", value(batch.testcaseKey()));
            graphRepository.saveEdge(new GraphRepository.GraphEdge(
                    GraphModels.edgeId(snapshot.id(), callerId, calleeId, GraphEdgeType.CALLS_RUNTIME, EvidenceKind.DYNAMIC_TRACE, executionId),
                    snapshot.id(), baselineId, projectId, callerId, calleeId, GraphEdgeType.CALLS_RUNTIME, EvidenceKind.DYNAMIC_TRACE, "E4",
                    1d, executionId, call.traceId() + "/" + call.spanId(), attributes));
            edges++;
        }

        // TOUCHED edges: TEST_EXECUTION -> every method node it exercised (D2 evidence, plan §5).
        for (String methodNodeId : touchedNodeIds) {
            graphRepository.saveEdge(new GraphRepository.GraphEdge(
                    GraphModels.edgeId(snapshot.id(), testExecNodeId, methodNodeId, GraphEdgeType.TOUCHED, EvidenceKind.DYNAMIC_TRACE, executionId),
                    snapshot.id(), baselineId, projectId, testExecNodeId, methodNodeId,
                    GraphEdgeType.TOUCHED, EvidenceKind.DYNAMIC_TRACE, "E3", 1d,
                    executionId, batch.executionId(),
                    Map.of("testcaseKey", value(batch.testcaseKey()), "commit", batch.sourceCommit())));
            edges++;
        }

        return new ProjectionResult(snapshot.id(), executionId, nodes, edges);
    }

    private String runtimeNode(GraphRepository.GraphSnapshot snapshot, String projectId, String baselineId, String symbolId,
                               String locator, String role) {
        return graphRepository.findActiveNodeBySymbol(baselineId, symbolId).map(GraphRepository.GraphNode::id).orElseGet(() -> {
            String id = "runtime-symbol:" + GraphModels.fingerprint(snapshot.id() + "|" + symbolId);
            graphRepository.saveNode(new GraphRepository.GraphNode(id, snapshot.id(), baselineId, projectId, GraphNodeKind.METHOD,
                    symbolId, symbolId, locator, symbolId, null, Map.of("runtimeOnly", true, "role", role)));
            return id;
        });
    }

    private void validate(Baseline baseline, RuntimeTraceBatch batch) {
        Assert.notNull(batch, "运行 Trace 批次不能为空");
        Assert.hasText(batch.executionId(), "executionId不能为空");
        Assert.hasText(batch.sourceCommit(), "sourceCommit不能为空");
        Assert.hasText(batch.environment(), "environment不能为空");
        Assert.hasText(batch.traceHash(), "traceHash不能为空");
        Assert.hasText(batch.collectorVersion(), "collectorVersion不能为空");
        Assert.notEmpty(batch.calls(), "calls不能为空");
        if (StringUtils.hasText(baseline.sourceCommit()) && !baseline.sourceCommit().equals(batch.sourceCommit())) {
            throw new IllegalArgumentException("Trace 的 sourceCommit 与分析基线不一致，不能作为当前基线的动态证据");
        }
    }

    private void validateCall(RuntimeCall call) {
        Assert.notNull(call, "调用记录不能为空");
        Assert.hasText(call.callerSymbolId(), "callerSymbolId不能为空");
        Assert.hasText(call.calleeSymbolId(), "calleeSymbolId不能为空");
        Assert.hasText(call.traceId(), "traceId不能为空");
        Assert.hasText(call.spanId(), "spanId不能为空");
        Assert.isTrue(call.count() > 0, "count必须大于0");
    }

    private String value(String text) { return text == null ? "" : text; }
    public record RuntimeTraceBatch(String executionId, String testcaseKey, String sourceCommit, String environment,
                                    String collectorVersion, String traceHash, OffsetDateTime startedAt,
                                    OffsetDateTime finishedAt, List<RuntimeCall> calls) {}
    public record RuntimeCall(String callerSymbolId, String calleeSymbolId, String callerLocator, String calleeLocator,
                              String traceId, String spanId, String parentSpanId, long count, double durationMs, String outcome) {}
    public record ProjectionResult(String snapshotId, String executionId, int nodeCount, int edgeCount) {}
}
