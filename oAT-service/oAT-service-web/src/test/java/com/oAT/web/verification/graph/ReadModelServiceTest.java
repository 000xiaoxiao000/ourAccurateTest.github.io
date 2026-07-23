package com.oAT.web.verification.graph;

import com.oAT.web.verification.VerificationRepository;
import com.oAT.web.verification.model.VerificationModels.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReadModelServiceTest {

    private GraphRepository graphRepository;
    private VerificationRepository verificationRepository;
    private FusionViewService fusionViewService;
    private ReadModelService service;

    private final List<GraphRepository.GraphAggregate> savedAggregates = new ArrayList<>();

    private static final String PROJECT   = "proj-1";
    private static final String BASELINE  = "bl-1";

    @BeforeEach
    void setUp() {
        graphRepository = mock(GraphRepository.class);
        verificationRepository = mock(VerificationRepository.class);
        fusionViewService = mock(FusionViewService.class);
        service = new ReadModelService(graphRepository, verificationRepository, fusionViewService);

        doNothing().when(graphRepository).invalidateAggregates(anyString(), anyString());
        doAnswer(inv -> { savedAggregates.add(inv.getArgument(0)); return null; })
                .when(graphRepository).saveAggregate(any());

        when(verificationRepository.findBaseline(PROJECT, BASELINE))
                .thenReturn(Optional.of(baseline(BASELINE)));
        when(fusionViewService.build(eq(PROJECT), eq(BASELINE), anyInt()))
                .thenReturn(new FusionViewService.FusionView(List.of(), 0, 0, 0, 0, false, null));
    }

    // ── impact_summary ────────────────────────────────────────────────────────

    @Test
    void impact_summary_counts_affected_acs_and_testcases_per_symbol() {
        AcceptanceCriterion ac = criterion("ac-1", "REQ-1", "AC-1");
        when(verificationRepository.findCriteria(BASELINE)).thenReturn(List.of(ac));
        when(verificationRepository.findTraceLinks(BASELINE)).thenReturn(List.of(
                link("l1", "AC", "ac-1", "SOURCE_SYMBOL", "sym-A"),
                link("l2", "AC", "ac-1", "TESTCASE",      "tc-1")));
        when(graphRepository.findActiveEdgesByType(eq(BASELINE), any())).thenReturn(List.of());

        service.rebuildAll(PROJECT, BASELINE);

        List<GraphRepository.GraphAggregate> impactRows = aggregatesOfKind(ReadModelService.IMPACT_SUMMARY);
        assertFalse(impactRows.isEmpty(), "impact_summary must have at least one row");
        GraphRepository.GraphAggregate row = impactRows.get(0);
        assertEquals("sym-A", row.payload().get("changedSymbol"));
        assertEquals(1, ((List<?>) row.payload().get("affectedAcIds")).size());
        assertEquals(1, ((List<?>) row.payload().get("affectedTestcaseIds")).size());
        assertEquals(1, row.payload().get("affectedAcCount"));
        assertEquals(1, row.payload().get("affectedTestcaseCount"));
    }

    @Test
    void impact_summary_is_empty_when_no_implementation_links() {
        AcceptanceCriterion ac = criterion("ac-1", "REQ-1", "AC-1");
        when(verificationRepository.findCriteria(BASELINE)).thenReturn(List.of(ac));
        // only testcase links, no SOURCE_SYMBOL
        when(verificationRepository.findTraceLinks(BASELINE)).thenReturn(List.of(
                link("l1", "AC", "ac-1", "TESTCASE", "tc-1")));
        when(graphRepository.findActiveEdgesByType(eq(BASELINE), any())).thenReturn(List.of());

        service.rebuildAll(PROJECT, BASELINE);

        assertTrue(aggregatesOfKind(ReadModelService.IMPACT_SUMMARY).isEmpty(),
                "impact_summary must be empty when no SOURCE_SYMBOL links exist");
    }

    // ── quality_gate_summary ──────────────────────────────────────────────────

    @Test
    void quality_gate_summary_computes_rates_correctly() {
        List<AcceptanceCriterion> criteria = List.of(
                criterion("ac-1", "REQ-1", "AC-1"),
                criterion("ac-2", "REQ-1", "AC-2"),
                criterion("ac-3", "REQ-1", "AC-3"),
                criterion("ac-4", "REQ-1", "AC-4"));
        when(verificationRepository.findCriteria(BASELINE)).thenReturn(criteria);
        // ac-1, ac-2 have testcase; ac-1 has implementation, coverage and execution; ac-3 has coverage only
        when(verificationRepository.findTraceLinks(BASELINE)).thenReturn(List.of(
                link("l1", "AC", "ac-1", "TESTCASE",      "tc-1"),
                link("l2", "AC", "ac-2", "TESTCASE",      "tc-2"),
                link("l3", "AC", "ac-1", "SOURCE_SYMBOL", "sym-A"),
                link("l4", "AC", "ac-1", "COVERAGE",      "cov-1"),
                link("l5", "AC", "ac-1", "EXECUTION",     "exec-1"),
                link("l6", "AC", "ac-3", "COVERAGE",      "cov-2")));
        when(graphRepository.findActiveEdgesByType(eq(BASELINE), any())).thenReturn(List.of());

        service.rebuildAll(PROJECT, BASELINE);

        List<GraphRepository.GraphAggregate> summaries = aggregatesOfKind(ReadModelService.QUALITY_GATE_SUMMARY);
        assertEquals(1, summaries.size(), "quality_gate_summary must produce exactly one row");
        Map<String, Object> p = summaries.get(0).payload();
        assertEquals(4, p.get("totalCriteria"));
        assertEquals(2, p.get("testcaseCoveredCount"));
        assertEquals(1, p.get("implementationCoveredCount"));
        assertEquals(2, p.get("coverageCoveredCount"));
        assertEquals(1, p.get("executionCoveredCount"));
        // closed loop = ACs with testcase + implementation + coverage + execution = ac-1 only
        assertEquals(1, p.get("closedLoopCount"));
        assertEquals(0.5, (double) p.get("testcaseCoverageRate"), 0.0001);
        assertEquals(0.25, (double) p.get("implementationCoverageRate"), 0.0001);
        assertEquals(0.25, (double) p.get("closedLoopRate"), 0.0001);
    }

    @Test
    void quality_gate_summary_handles_zero_criteria() {
        when(verificationRepository.findCriteria(BASELINE)).thenReturn(List.of());
        when(verificationRepository.findTraceLinks(BASELINE)).thenReturn(List.of());
        when(graphRepository.findActiveEdgesByType(eq(BASELINE), any())).thenReturn(List.of());

        service.rebuildAll(PROJECT, BASELINE);

        List<GraphRepository.GraphAggregate> summaries = aggregatesOfKind(ReadModelService.QUALITY_GATE_SUMMARY);
        assertEquals(1, summaries.size());
        assertEquals(0, summaries.get(0).payload().get("totalCriteria"));
        assertEquals(0.0, (double) summaries.get(0).payload().get("closedLoopRate"), 0.0001);
    }

    @Test
    void rebuildAll_result_includes_new_model_counts() {
        when(verificationRepository.findCriteria(BASELINE)).thenReturn(List.of());
        when(verificationRepository.findTraceLinks(BASELINE)).thenReturn(List.of());
        when(graphRepository.findActiveEdgesByType(eq(BASELINE), any())).thenReturn(List.of());

        ReadModelService.RebuildResult result = service.rebuildAll(PROJECT, BASELINE);

        assertEquals(0, result.impactSummaryRows());
        assertEquals(1, result.qualityGateSummaryRows());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private List<GraphRepository.GraphAggregate> aggregatesOfKind(String kind) {
        return savedAggregates.stream().filter(a -> a.kind().equals(kind)).toList();
    }

    private com.oAT.web.verification.model.VerificationModels.Baseline baseline(String id) {
        return new com.oAT.web.verification.model.VerificationModels.Baseline(
                id, PROJECT, "baseline-name",
                null, null, null, null, null, null, null, null, null,
                "v1", com.oAT.web.verification.model.VerificationModels.BaselineStatus.COMPLETED,
                com.oAT.web.verification.model.VerificationModels.Freshness.LIVE,
                "test", java.time.LocalDateTime.now(), java.time.LocalDateTime.now());
    }

    private AcceptanceCriterion criterion(String id, String reqKey, String acKey) {
        return new AcceptanceCriterion(id, BASELINE, reqKey, acKey, "content", "detail",
                null, "P1", true, false, 0.9);
    }

    private TraceLink link(String id, String srcType, String srcId, String tgtType, String tgtId) {
        return new TraceLink(id, BASELINE, srcType, srcId, tgtType, tgtId,
                "VERIFIED_BY", "TEST", 0.9, EvidenceLevel.E1, ReviewStatus.PENDING, Map.of());
    }
}
