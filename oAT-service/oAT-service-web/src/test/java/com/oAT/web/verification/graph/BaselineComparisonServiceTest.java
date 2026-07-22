package com.oAT.web.verification.graph;

import com.oAT.web.verification.VerificationRepository;
import com.oAT.web.verification.model.VerificationModels.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BaselineComparisonServiceTest {

    private GraphRepository graphRepository;
    private VerificationRepository verificationRepository;
    private FusionViewService fusionViewService;
    private BaselineComparisonService service;

    private static final String PROJECT = "proj-1";
    private static final String BASE_ID  = "baseline-old";
    private static final String TARGET_ID = "baseline-new";

    @BeforeEach
    void setUp() {
        graphRepository = mock(GraphRepository.class);
        verificationRepository = mock(VerificationRepository.class);
        fusionViewService = mock(FusionViewService.class);
        service = new BaselineComparisonService(graphRepository, verificationRepository, fusionViewService);

        doNothing().when(graphRepository).saveAggregate(any());

        // default: both baselines exist
        stubBaseline(BASE_ID);
        stubBaseline(TARGET_ID);
    }

    @Test
    void same_baseline_id_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.compare(PROJECT, BASE_ID, BASE_ID));
    }

    @Test
    void missing_baseline_throws() {
        when(verificationRepository.findBaseline(PROJECT, "missing"))
                .thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> service.compare(PROJECT, "missing", TARGET_ID));
    }

    @Test
    void ac_added_in_target_appears_as_ADDED_delta() {
        AcceptanceCriterion ac = criterion("ac-1", BASE_ID, "REQ-1", "AC-1");
        // base has no ACs; target has one
        when(verificationRepository.findCriteria(BASE_ID)).thenReturn(List.of());
        when(verificationRepository.findTraceLinks(BASE_ID)).thenReturn(List.of());
        when(verificationRepository.findCriteria(TARGET_ID)).thenReturn(List.of(ac));
        when(verificationRepository.findTraceLinks(TARGET_ID)).thenReturn(List.of());
        stubEmptyFusion(BASE_ID);
        stubEmptyFusion(TARGET_ID);

        var result = service.compare(PROJECT, BASE_ID, TARGET_ID);

        assertEquals(1, result.acDeltas().size());
        assertEquals("ADDED", result.acDeltas().get(0).changeType());
        assertEquals("AC-1", result.acDeltas().get(0).acKey());
    }

    @Test
    void ac_removed_from_target_appears_as_REMOVED_delta() {
        AcceptanceCriterion ac = criterion("ac-1", BASE_ID, "REQ-1", "AC-1");
        when(verificationRepository.findCriteria(BASE_ID)).thenReturn(List.of(ac));
        when(verificationRepository.findTraceLinks(BASE_ID)).thenReturn(List.of());
        when(verificationRepository.findCriteria(TARGET_ID)).thenReturn(List.of());
        when(verificationRepository.findTraceLinks(TARGET_ID)).thenReturn(List.of());
        stubEmptyFusion(BASE_ID);
        stubEmptyFusion(TARGET_ID);

        var result = service.compare(PROJECT, BASE_ID, TARGET_ID);

        assertEquals(1, result.acDeltas().size());
        assertEquals("REMOVED", result.acDeltas().get(0).changeType());
    }

    @Test
    void ac_with_gained_testcase_evidence_appears_as_EVIDENCE_CHANGED() {
        AcceptanceCriterion ac = criterion("ac-1", BASE_ID, "REQ-1", "AC-1");
        AcceptanceCriterion acNew = criterion("ac-1", TARGET_ID, "REQ-1", "AC-1");
        // base: no evidence; target: testcase linked
        when(verificationRepository.findCriteria(BASE_ID)).thenReturn(List.of(ac));
        when(verificationRepository.findTraceLinks(BASE_ID)).thenReturn(List.of());
        when(verificationRepository.findCriteria(TARGET_ID)).thenReturn(List.of(acNew));
        when(verificationRepository.findTraceLinks(TARGET_ID)).thenReturn(List.of(
                link("l1", TARGET_ID, "AC", "ac-1", "TESTCASE", "tc-1")));
        stubEmptyFusion(BASE_ID);
        stubEmptyFusion(TARGET_ID);

        var result = service.compare(PROJECT, BASE_ID, TARGET_ID);

        assertEquals(1, result.acDeltas().size());
        assertEquals("EVIDENCE_CHANGED", result.acDeltas().get(0).changeType());
        assertEquals("----", result.acDeltas().get(0).beforeEvidence());
        assertEquals("T---", result.acDeltas().get(0).afterEvidence());
    }

    @Test
    void no_changes_produces_empty_deltas() {
        AcceptanceCriterion ac = criterion("ac-1", BASE_ID, "REQ-1", "AC-1");
        AcceptanceCriterion acSame = criterion("ac-1", TARGET_ID, "REQ-1", "AC-1");
        when(verificationRepository.findCriteria(BASE_ID)).thenReturn(List.of(ac));
        when(verificationRepository.findCriteria(TARGET_ID)).thenReturn(List.of(acSame));
        when(verificationRepository.findTraceLinks(anyString())).thenReturn(List.of());
        stubEmptyFusion(BASE_ID);
        stubEmptyFusion(TARGET_ID);

        var result = service.compare(PROJECT, BASE_ID, TARGET_ID);

        assertTrue(result.acDeltas().isEmpty());
        assertTrue(result.fusionDeltas().isEmpty());
    }

    @Test
    void fusion_state_transition_appears_in_fusionDeltas() {
        when(verificationRepository.findCriteria(anyString())).thenReturn(List.of());
        when(verificationRepository.findTraceLinks(anyString())).thenReturn(List.of());

        // base: symbol A is REACHABLE_NOT_EXECUTED; target: EXECUTED_CONFIRMED
        when(fusionViewService.build(PROJECT, BASE_ID, 5_000)).thenReturn(
                new FusionViewService.FusionView(
                        List.of(fusionNode("sym-A", "MethodA", "REACHABLE_NOT_EXECUTED")),
                        1, 0, 1, 0, false, null));
        when(fusionViewService.build(PROJECT, TARGET_ID, 5_000)).thenReturn(
                new FusionViewService.FusionView(
                        List.of(fusionNode("sym-A", "MethodA", "EXECUTED_CONFIRMED")),
                        1, 1, 0, 0, false, null));

        var result = service.compare(PROJECT, BASE_ID, TARGET_ID);

        assertEquals(1, result.fusionDeltas().size());
        assertEquals("REACHABLE_NOT_EXECUTED", result.fusionDeltas().get(0).beforeState());
        assertEquals("EXECUTED_CONFIRMED", result.fusionDeltas().get(0).afterState());
    }

    @Test
    void result_ids_are_preserved() {
        when(verificationRepository.findCriteria(anyString())).thenReturn(List.of());
        when(verificationRepository.findTraceLinks(anyString())).thenReturn(List.of());
        stubEmptyFusion(BASE_ID);
        stubEmptyFusion(TARGET_ID);

        var result = service.compare(PROJECT, BASE_ID, TARGET_ID);

        assertEquals(BASE_ID, result.baseBaselineId());
        assertEquals(TARGET_ID, result.targetBaselineId());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void stubBaseline(String id) {
        when(verificationRepository.findBaseline(PROJECT, id))
                .thenReturn(Optional.of(new com.oAT.web.verification.model.VerificationModels.Baseline(
                        id, PROJECT, "baseline-name",
                        null, null, null, null, null, null, null, null, null,
                        "v1", com.oAT.web.verification.model.VerificationModels.BaselineStatus.COMPLETED,
                        com.oAT.web.verification.model.VerificationModels.Freshness.LIVE,
                        "test", java.time.LocalDateTime.now(), java.time.LocalDateTime.now())));
    }

    private void stubEmptyFusion(String baselineId) {
        when(fusionViewService.build(PROJECT, baselineId, 5_000))
                .thenReturn(new FusionViewService.FusionView(List.of(), 0, 0, 0, 0, false, null));
    }

    private AcceptanceCriterion criterion(String id, String baselineId, String reqKey, String acKey) {
        return new AcceptanceCriterion(id, baselineId, reqKey, acKey,
                "content", "detail", null, "P1", true, false, 0.9);
    }

    private TraceLink link(String id, String baselineId, String srcType, String srcId,
                           String tgtType, String tgtId) {
        return new TraceLink(id, baselineId, srcType, srcId, tgtType, tgtId,
                "VERIFIED_BY", "TEST", 0.9, EvidenceLevel.E1, ReviewStatus.PENDING, Map.of());
    }

    private FusionViewService.FusionNode fusionNode(String symbolId, String displayName, String state) {
        return new FusionViewService.FusionNode(
                "node-" + symbolId, symbolId, displayName, displayName + ".java", state);
    }
}
