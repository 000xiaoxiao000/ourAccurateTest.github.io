package com.oAT.web.verification.graph;

import com.oAT.web.verification.VerificationRepository;
import com.oAT.web.verification.model.VerificationModels.AnalysisJob;
import com.oAT.web.verification.model.VerificationModels.AnalysisJobStatus;
import com.oAT.web.verification.model.VerificationModels.TraceLink;
import com.oAT.web.verification.model.VerificationModels.EvidenceLevel;
import com.oAT.web.verification.model.VerificationModels.ReviewStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IncrementalRecomputeServiceTest {

    private GraphRepository graphRepository;
    private VerificationRepository verificationRepository;
    private AnalysisJobService jobService;
    private IncrementalRecomputeService service;

    private static final String PROJECT  = "proj-1";
    private static final String BASELINE = "bl-1";

    private final List<GraphRepository.GraphAggregate> savedAggregates = new ArrayList<>();
    private final List<String> checkpointSteps = new ArrayList<>();

    @BeforeEach
    void setUp() {
        graphRepository = mock(GraphRepository.class);
        verificationRepository = mock(VerificationRepository.class);
        jobService = mock(AnalysisJobService.class);
        service = new IncrementalRecomputeService(graphRepository, verificationRepository, jobService);

        when(verificationRepository.findBaseline(PROJECT, BASELINE))
                .thenReturn(Optional.of(baseline(BASELINE)));
        doNothing().when(graphRepository).invalidateNodeSubtree(anyString(), any());
        doNothing().when(graphRepository).saveAggregate(any());
        doNothing().when(jobService).succeed(anyString());
        doAnswer(inv -> { checkpointSteps.add(inv.getArgument(1)); return null; })
                .when(jobService).checkpoint(anyString(), anyString(), any());
        when(graphRepository.invalidateAggregateSubject(anyString(), anyString(), anyString())).thenReturn(1);
    }

    // ── cache hit ─────────────────────────────────────────────────────────────

    @Test
    void cache_hit_returns_result_without_touching_graph() {
        AnalysisJob cached = succeededJobWithPayload(Map.of(
                "affectedSymbols", List.of("com.example.Foo#bar"),
                "affectedNodeCount", 2,
                "affectedAcIds", List.of("ac-1"),
                "affectedTestcaseIds", List.of("tc-1"),
                "staleAggregates", 1));
        when(jobService.submit(any(), any(), any(), any(), any()))
                .thenReturn(new AnalysisJobService.SubmitResult(
                        "cached-job", AnalysisJobService.SubmitOutcome.CACHE_HIT, cached));

        var result = service.recompute(PROJECT, BASELINE, List.of("com.example.Foo#bar"));

        assertEquals(List.of("com.example.Foo#bar"), result.affectedSymbols());
        assertEquals(2, result.affectedNodeCount());
        assertEquals(List.of("ac-1"), result.affectedAcIds());
        verifyNoInteractions(graphRepository);
    }

    @Test
    void deduplicated_job_returns_checkpoint_result_without_recompute() {
        AnalysisJob active = runningJobWithPayload(Map.of(
                "affectedSymbols", List.of("sym.X"),
                "affectedNodeCount", 0,
                "affectedAcIds", List.of(),
                "affectedTestcaseIds", List.of(),
                "staleAggregates", 0));
        when(jobService.submit(any(), any(), any(), any(), any()))
                .thenReturn(new AnalysisJobService.SubmitResult(
                        "dup-job", AnalysisJobService.SubmitOutcome.DEDUPLICATED, active));

        var result = service.recompute(PROJECT, BASELINE, List.of("sym.X"));

        assertEquals(List.of("sym.X"), result.affectedSymbols());
        verifyNoInteractions(graphRepository);
    }

    // ── full execution path ───────────────────────────────────────────────────

    @Test
    void full_run_checkpoints_all_four_steps() {
        stubNewJob();
        stubEmptyGraph();
        when(verificationRepository.findTraceLinks(BASELINE)).thenReturn(List.of());

        service.recompute(PROJECT, BASELINE, List.of("com.example.Service#process"));

        assertTrue(checkpointSteps.contains(IncrementalRecomputeService.STEP_LOCATE_ROOTS));
        assertTrue(checkpointSteps.contains(IncrementalRecomputeService.STEP_WALK_SUBTREE));
        assertTrue(checkpointSteps.contains(IncrementalRecomputeService.STEP_INVALIDATE));
        assertTrue(checkpointSteps.contains(IncrementalRecomputeService.STEP_MARK_ACS));
        verify(jobService).succeed(anyString());
    }

    @Test
    void affected_acs_identified_through_SOURCE_SYMBOL_links() {
        stubNewJob();
        when(graphRepository.findNodesBySymbolPrefix(BASELINE,
                com.oAT.web.verification.model.GraphModels.GraphNodeKind.METHOD, "com.example.Svc#save"))
                .thenReturn(List.of(methodNode("node-1", "com.example.Svc#save")));
        when(graphRepository.findMethodSubtree(eq(BASELINE), any(), anyInt()))
                .thenReturn(List.of(methodNode("node-1", "com.example.Svc#save")));
        when(verificationRepository.findTraceLinks(BASELINE)).thenReturn(List.of(
                link("l1", "AC", "ac-1", "SOURCE_SYMBOL", "com.example.Svc#save"),
                link("l2", "AC", "ac-1", "TESTCASE",      "tc-1")));

        var result = service.recompute(PROJECT, BASELINE, List.of("com.example.Svc#save"));

        assertEquals(List.of("ac-1"), result.affectedAcIds());
        assertEquals(List.of("tc-1"), result.affectedTestcaseIds());
        assertTrue(result.staleAggregates() > 0);
        verify(graphRepository).invalidateAggregateSubject(
                eq(BASELINE), eq(AcceptanceCriterionFusionService.AGGREGATE_KIND), eq("ac-1"));
        verify(graphRepository).invalidateAggregateSubject(
                eq(BASELINE), eq(AssertionConsistencyService.AGGREGATE_KIND), eq("ac-1"));
    }

    @Test
    void no_matching_nodes_produces_empty_result_with_success() {
        stubNewJob();
        when(graphRepository.findNodesBySymbolPrefix(any(), any(), anyString()))
                .thenReturn(List.of());
        when(graphRepository.findMethodSubtree(any(), any(), anyInt())).thenReturn(List.of());
        when(verificationRepository.findTraceLinks(BASELINE)).thenReturn(List.of());

        var result = service.recompute(PROJECT, BASELINE, List.of("nonexistent.Symbol#method"));

        assertEquals(0, result.affectedNodeCount());
        assertTrue(result.affectedAcIds().isEmpty());
        verify(jobService).succeed(anyString());
    }

    @Test
    void exception_during_execution_fails_job_and_rethrows() {
        stubNewJob();
        when(graphRepository.findNodesBySymbolPrefix(any(), any(), anyString()))
                .thenThrow(new RuntimeException("DB error"));

        assertThrows(RuntimeException.class,
                () -> service.recompute(PROJECT, BASELINE, List.of("sym.Broken#method")));

        verify(jobService).fail(anyString(), contains("DB error"));
    }

    @Test
    void blank_symbols_are_skipped_gracefully() {
        stubNewJob();
        stubEmptyGraph();
        when(verificationRepository.findTraceLinks(BASELINE)).thenReturn(List.of());

        var result = service.recompute(PROJECT, BASELINE, List.of("  ", "com.example.X#m"));

        assertNotNull(result);
        verify(jobService).succeed(anyString());
    }

    @Test
    void missing_baseline_throws_before_job_submission() {
        when(verificationRepository.findBaseline(PROJECT, "missing")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.recompute(PROJECT, "missing", List.of("sym.A")));
        verifyNoInteractions(jobService);
    }

    @Test
    void empty_changed_symbols_throws_before_job_submission() {
        assertThrows(IllegalArgumentException.class,
                () -> service.recompute(PROJECT, BASELINE, List.of()));
        verifyNoInteractions(jobService);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void stubNewJob() {
        AnalysisJob newJob = new AnalysisJob("job-new", PROJECT, BASELINE,
                AnalysisJobService.JOB_INCREMENTAL_RECOMPUTE, "hash-x",
                AnalysisJobStatus.QUEUED, null, "system",
                LocalDateTime.now(), LocalDateTime.now(), null, null, Map.of(), 0, 3);
        when(jobService.submit(any(), any(), any(), any(), any()))
                .thenReturn(new AnalysisJobService.SubmitResult(
                        "job-new", AnalysisJobService.SubmitOutcome.ENQUEUED, newJob));
    }

    private void stubEmptyGraph() {
        when(graphRepository.findNodesBySymbolPrefix(any(), any(), anyString())).thenReturn(List.of());
        when(graphRepository.findMethodSubtree(any(), any(), anyInt())).thenReturn(List.of());
    }

    private GraphRepository.GraphNode methodNode(String id, String stableSymbolId) {
        return new GraphRepository.GraphNode(id, "snap-1", BASELINE, PROJECT,
                com.oAT.web.verification.model.GraphModels.GraphNodeKind.METHOD,
                stableSymbolId, stableSymbolId, "path:10", stableSymbolId, null, Map.of());
    }

    private TraceLink link(String id, String srcType, String srcId, String tgtType, String tgtId) {
        return new TraceLink(id, BASELINE, srcType, srcId, tgtType, tgtId,
                "VERIFIED_BY", "TEST", 0.9, EvidenceLevel.E1, ReviewStatus.PENDING, Map.of());
    }

    private AnalysisJob succeededJobWithPayload(Map<String, Object> payload) {
        return new AnalysisJob("cached-job", PROJECT, BASELINE,
                AnalysisJobService.JOB_INCREMENTAL_RECOMPUTE, "hash-c",
                AnalysisJobStatus.SUCCEEDED, null, "system",
                LocalDateTime.now().minusMinutes(5), LocalDateTime.now(), LocalDateTime.now(),
                IncrementalRecomputeService.STEP_MARK_ACS, payload, 0, 3);
    }

    private AnalysisJob runningJobWithPayload(Map<String, Object> payload) {
        return new AnalysisJob("dup-job", PROJECT, BASELINE,
                AnalysisJobService.JOB_INCREMENTAL_RECOMPUTE, "hash-d",
                AnalysisJobStatus.RUNNING, null, "system",
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now(), null,
                IncrementalRecomputeService.STEP_WALK_SUBTREE, payload, 0, 3);
    }

    private com.oAT.web.verification.model.VerificationModels.Baseline baseline(String id) {
        return new com.oAT.web.verification.model.VerificationModels.Baseline(
                id, PROJECT, "name", null, null, null, null, null, null,
                null, null, null, "v1",
                com.oAT.web.verification.model.VerificationModels.BaselineStatus.COMPLETED,
                com.oAT.web.verification.model.VerificationModels.Freshness.LIVE,
                "test", LocalDateTime.now(), LocalDateTime.now());
    }
}
