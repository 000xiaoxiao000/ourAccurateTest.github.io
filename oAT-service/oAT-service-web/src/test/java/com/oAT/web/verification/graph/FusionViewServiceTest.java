package com.oAT.web.verification.graph;

import com.oAT.web.esDao.ClassCoverageIndexRepository;
import com.oAT.web.esDao.entity.ClassCoverageIndex;
import com.oAT.web.verification.VerificationRepository;
import com.oAT.web.verification.model.GraphModels.GraphNodeKind;
import com.oAT.web.verification.model.VerificationModels;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FusionViewServiceTest {
    private static final String PROJECT = "project";
    private static final String BASELINE = "baseline";
    private static final String COVERAGE = "coverage";

    private GraphRepository graphRepository;
    private ClassCoverageIndexRepository coverageRepository;
    private FusionViewService service;

    @BeforeEach
    void setUp() {
        graphRepository = mock(GraphRepository.class);
        VerificationRepository verificationRepository = mock(VerificationRepository.class);
        coverageRepository = mock(ClassCoverageIndexRepository.class);
        service = new FusionViewService(graphRepository, verificationRepository, coverageRepository);
        when(verificationRepository.findBaseline(PROJECT, BASELINE)).thenReturn(Optional.of(baseline()));
        when(graphRepository.findDynamicallyEvidencedNodeIds(BASELINE)).thenReturn(Set.of());
        when(graphRepository.findStaticallyReachableNodeIds(BASELINE)).thenReturn(Set.of());
    }

    @Test
    void classifies_covered_method_from_coverage_tab_as_executed_when_symbol_has_repository_prefix() {
        when(graphRepository.findActiveNodesByKind(BASELINE, GraphNodeKind.METHOD)).thenReturn(List.of(method("allHkAmount", 77)));
        when(coverageRepository.findByReportId(COVERAGE)).thenReturn(List.of(coverage("com/example/DetailController", "allHkAmount")));

        FusionViewService.FusionView view = service.build(PROJECT, BASELINE, 100);

        assertEquals(1, view.executedConfirmed());
        assertEquals("EXECUTED_CONFIRMED", view.nodes().get(0).fusionState());
    }

    @Test
    void classifies_static_entrypoint_without_call_edge_as_reachable_not_executed() {
        GraphRepository.GraphNode entrypoint = method("entrypoint", 12);
        when(graphRepository.findActiveNodesByKind(BASELINE, GraphNodeKind.METHOD)).thenReturn(List.of(entrypoint));
        when(coverageRepository.findByReportId(COVERAGE)).thenReturn(List.of());
        when(graphRepository.findStaticallyReachableNodeIds(BASELINE)).thenReturn(Set.of(entrypoint.id()));

        FusionViewService.FusionView view = service.build(PROJECT, BASELINE, 100);

        assertEquals(1, view.reachableNotExecuted());
        assertEquals(0, view.notObservable());
        assertEquals("REACHABLE_NOT_EXECUTED", view.nodes().get(0).fusionState());
    }

    @Test
    void dynamic_evidence_takes_precedence_over_static_reachability() {
        GraphRepository.GraphNode method = method("coveredAndReachable", 12);
        when(graphRepository.findActiveNodesByKind(BASELINE, GraphNodeKind.METHOD)).thenReturn(List.of(method));
        when(coverageRepository.findByReportId(COVERAGE)).thenReturn(List.of());
        when(graphRepository.findDynamicallyEvidencedNodeIds(BASELINE)).thenReturn(Set.of(method.id()));
        when(graphRepository.findStaticallyReachableNodeIds(BASELINE)).thenReturn(Set.of(method.id()));

        FusionViewService.FusionView view = service.build(PROJECT, BASELINE, 100);

        assertEquals(1, view.executedConfirmed());
        assertEquals(0, view.reachableNotExecuted());
        assertEquals("EXECUTED_CONFIRMED", view.nodes().get(0).fusionState());
    }

    @Test
    void does_not_mark_every_static_method_as_reachable_without_a_static_edge() {
        when(graphRepository.findActiveNodesByKind(BASELINE, GraphNodeKind.METHOD)).thenReturn(List.of(method("orphan", 12)));
        when(coverageRepository.findByReportId(COVERAGE)).thenReturn(List.of());

        FusionViewService.FusionView view = service.build(PROJECT, BASELINE, 100);

        assertEquals(0, view.reachableNotExecuted());
        assertEquals(1, view.notObservable());
        assertEquals("NOT_OBSERVABLE", view.nodes().get(0).fusionState());
    }

    private GraphRepository.GraphNode method(String name, int line) {
        return new GraphRepository.GraphNode("method-" + name, "snapshot", BASELINE, PROJECT, GraphNodeKind.METHOD,
                "repo@commit:com/example/DetailController.java:com.example.DetailController#" + name + "(java.lang.String):java.lang.Object",
                "repo:com/example/DetailController.java:com.example.DetailController#" + name + "(java.lang.String):java.lang.Object",
                "com/example/DetailController.java:" + line, name, null, Map.of("line", line));
    }

    private ClassCoverageIndex coverage(String className, String methodName) {
        ClassCoverageIndex index = new ClassCoverageIndex();
        index.setClassName(className);
        ClassCoverageIndex.MethodCoverageDetail method = new ClassCoverageIndex.MethodCoverageDetail();
        method.setClassName(className);
        method.setMethodName(methodName);
        method.setCovered(true);
        index.setMethods(List.of(method));
        return index;
    }

    private VerificationModels.Baseline baseline() {
        return new VerificationModels.Baseline(BASELINE, PROJECT, "baseline", null, null, null, null, COVERAGE, null,
                null, null, null, "v1", VerificationModels.BaselineStatus.COMPLETED,
                VerificationModels.Freshness.LIVE, "test", LocalDateTime.now(), LocalDateTime.now());
    }
}
