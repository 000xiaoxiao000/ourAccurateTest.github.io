package com.oAT.web.verification.graph;

import com.oAT.web.verification.VerificationRepository;
import com.oAT.web.verification.model.GraphModels;
import com.oAT.web.verification.model.GraphModels.EvidenceKind;
import com.oAT.web.verification.model.GraphModels.GraphEdgeType;
import com.oAT.web.verification.model.GraphModels.GraphNodeKind;
import com.oAT.web.verification.model.GraphModels.SnapshotKind;
import com.oAT.web.verification.model.VerificationModels.Baseline;
import com.oAT.web.verification.model.VerificationModels.BaselineStatus;
import com.oAT.web.verification.model.VerificationModels.Freshness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RuntimeTraceProjectionServiceTest {

    private GraphRepository graphRepository;
    private VerificationRepository verificationRepository;
    private RuntimeTraceProjectionService service;

    private static final String PROJECT  = "proj-1";
    private static final String BASELINE = "bl-1";
    private static final String COMMIT   = "abc1234";

    @BeforeEach
    void setUp() {
        graphRepository = mock(GraphRepository.class);
        verificationRepository = mock(VerificationRepository.class);
        service = new RuntimeTraceProjectionService(graphRepository, verificationRepository);

        when(verificationRepository.findBaseline(PROJECT, BASELINE))
                .thenReturn(Optional.of(baseline(BASELINE, COMMIT)));
        doNothing().when(graphRepository).invalidateSnapshots(any(), any());
        doNothing().when(graphRepository).saveSnapshot(any());
        doNothing().when(graphRepository).saveRuntimeExecution(any());
        doNothing().when(verificationRepository).saveRuntimeTestExecution(any());
        doNothing().when(graphRepository).saveNode(any());
        doNothing().when(graphRepository).saveEdge(any());
        when(graphRepository.findActiveNodeBySymbol(any(), any())).thenReturn(Optional.empty());
    }

    // ── basic projection ──────────────────────────────────────────────────────

    @Test
    void project_saves_snapshot_and_runtime_execution() {
        service.project(PROJECT, BASELINE, batch("exec-1", COMMIT, List.of(call("A#m", "B#n"))));

        verify(graphRepository).saveSnapshot(argThat(s -> s.kind() == SnapshotKind.RUNTIME_TRACE));
        verify(graphRepository).saveRuntimeExecution(any());
        verify(verificationRepository).saveRuntimeTestExecution(any());
    }

    @Test
    void project_creates_TEST_EXECUTION_graph_node() {
        service.project(PROJECT, BASELINE, batch("exec-2", COMMIT, List.of(call("X#a", "Y#b"))));

        verify(graphRepository, atLeastOnce()).saveNode(argThat(n ->
                n.kind() == GraphNodeKind.TEST_EXECUTION));
    }

    @Test
    void project_saves_CALLS_RUNTIME_edge_for_each_call() {
        var batch = batch("exec-3", COMMIT, List.of(call("Svc#create", "Repo#save")));
        service.project(PROJECT, BASELINE, batch);

        verify(graphRepository, atLeastOnce()).saveEdge(argThat(e ->
                e.type() == GraphEdgeType.CALLS_RUNTIME
                        && e.evidenceKind() == EvidenceKind.DYNAMIC_TRACE
                        && "E4".equals(e.evidenceLevel())));
    }

    @Test
    void project_saves_TOUCHED_edges_for_all_exercised_methods() {
        // Two distinct calls — caller A, callee B, callee C → 3 unique touched nodes
        var batch = batch("exec-4", COMMIT, List.of(
                call("A#m", "B#n"),
                call("A#m", "C#k")));
        service.project(PROJECT, BASELINE, batch);

        verify(graphRepository, atLeastOnce()).saveEdge(argThat(e ->
                e.type() == GraphEdgeType.TOUCHED
                        && e.evidenceKind() == EvidenceKind.DYNAMIC_TRACE
                        && "E3".equals(e.evidenceLevel())));
    }

    @Test
    void project_result_contains_positive_node_and_edge_counts() {
        var result = service.project(PROJECT, BASELINE,
                batch("exec-5", COMMIT, List.of(call("A#m", "B#n"))));

        assertTrue(result.nodeCount() > 0, "nodeCount must be > 0");
        assertTrue(result.edgeCount() > 0, "edgeCount must be > 0");
        assertNotNull(result.snapshotId());
        assertNotNull(result.executionId());
    }

    @Test
    void project_reuses_existing_graph_node_when_symbol_already_present() {
        // Caller already exists as a graph node — should not create a new one
        String existingId = "existing-node-id";
        when(graphRepository.findActiveNodeBySymbol(BASELINE, "com.example.Svc#process"))
                .thenReturn(Optional.of(methodNode(existingId, "com.example.Svc#process")));

        service.project(PROJECT, BASELINE,
                batch("exec-6", COMMIT, List.of(call("com.example.Svc#process", "B#n"))));

        // saveNode should NOT be called for the existing symbol
        verify(graphRepository, never()).saveNode(argThat(n ->
                existingId.equals(n.id())));
    }

    // ── commit mismatch guard ─────────────────────────────────────────────────

    @Test
    void project_rejects_batch_with_mismatched_commit() {
        var batch = batch("exec-7", "other-commit", List.of(call("A#m", "B#n")));
        assertThrows(IllegalArgumentException.class,
                () -> service.project(PROJECT, BASELINE, batch));
    }

    @Test
    void project_rejects_empty_calls_list() {
        var batch = batch("exec-8", COMMIT, List.of());
        assertThrows(IllegalArgumentException.class,
                () -> service.project(PROJECT, BASELINE, batch));
    }

    @Test
    void project_rejects_null_batch() {
        assertThrows(IllegalArgumentException.class,
                () -> service.project(PROJECT, BASELINE, null));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private RuntimeTraceProjectionService.RuntimeTraceBatch batch(
            String executionId, String commit, List<RuntimeTraceProjectionService.RuntimeCall> calls) {
        return new RuntimeTraceProjectionService.RuntimeTraceBatch(
                executionId, "TC-001", commit, "ci",
                "agent-v1", GraphModels.fingerprint(executionId),
                OffsetDateTime.now().minusMinutes(5), OffsetDateTime.now(), calls);
    }

    private RuntimeTraceProjectionService.RuntimeCall call(String caller, String callee) {
        return new RuntimeTraceProjectionService.RuntimeCall(
                caller, callee, null, null,
                "trace-" + caller, "span-" + caller, null, 1, 10.0, "OK");
    }

    private GraphRepository.GraphNode methodNode(String id, String symbol) {
        return new GraphRepository.GraphNode(id, "snap-1", BASELINE, PROJECT,
                GraphNodeKind.METHOD, symbol, symbol, "path:1", symbol, null, Map.of());
    }

    private Baseline baseline(String id, String commit) {
        return new Baseline(id, PROJECT, "name", null, null, null, null, null, null,
                "http://repo", "main", commit, "v1",
                BaselineStatus.COMPLETED, Freshness.LIVE,
                "test", LocalDateTime.now(), LocalDateTime.now());
    }
}
