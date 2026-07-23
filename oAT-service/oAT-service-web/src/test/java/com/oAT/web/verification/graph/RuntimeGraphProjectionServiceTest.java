package com.oAT.web.verification.graph;

import com.oAT.web.coverage.universal.CoverageReportService;
import com.oAT.web.esDao.ClassCoverageIndexRepository;
import com.oAT.web.esDao.entity.ClassCoverageIndex;
import com.oAT.web.service.AppService;
import com.oAT.web.verification.VerificationRepository;
import com.oAT.web.verification.model.GraphModels;
import com.oAT.web.verification.model.VerificationModels;
import com.oAT.web.verification.storage.AssetContentStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeGraphProjectionServiceTest {
    private static final String PROJECT = "project";
    private static final String BASELINE = "baseline";
    private static final String COVERAGE = "coverage";

    private GraphRepository graphRepository;
    private ClassCoverageIndexRepository coverageRepository;
    private VerificationRepository verificationRepository;
    private RuntimeGraphProjectionService service;

    @BeforeEach
    void setUp() {
        graphRepository = mock(GraphRepository.class);
        coverageRepository = mock(ClassCoverageIndexRepository.class);
        verificationRepository = mock(VerificationRepository.class);
        service = new RuntimeGraphProjectionService(graphRepository, coverageRepository, verificationRepository,
                mock(CoverageReportService.class), mock(AppService.class), mock(AssetContentStore.class));

        when(verificationRepository.findBaseline(PROJECT, BASELINE)).thenReturn(Optional.of(baseline()));
        when(graphRepository.findActiveNodeByLocator(any(), any())).thenReturn(Optional.empty());
        when(graphRepository.findActiveMethod(any(), any(), any(), any(), anyInt())).thenReturn(Optional.empty());
    }

    @Test
    void projects_coverage_only_method_when_static_method_is_not_matched() {
        when(coverageRepository.findByReportId(COVERAGE)).thenReturn(List.of(coverage("com/example/DetailController", "allHkAmount")));

        RuntimeGraphProjectionService.ProjectionResult result = service.projectCoverage(PROJECT, BASELINE);

        assertEquals(3, result.nodeCount());
        assertEquals(1, result.edgeCount());
        ArgumentCaptor<GraphRepository.GraphNode> nodeCaptor = ArgumentCaptor.forClass(GraphRepository.GraphNode.class);
        verify(graphRepository, org.mockito.Mockito.atLeastOnce()).saveNode(nodeCaptor.capture());
        assertTrue(nodeCaptor.getAllValues().stream().anyMatch(node ->
                node.kind() == GraphModels.GraphNodeKind.METHOD
                        && node.id().startsWith("coverage-method:")
                        && node.displayName().equals("com.example.DetailController#allHkAmount")
                        && "COVERAGE_ONLY".equals(node.attributes().get("resolution"))));
    }

    private ClassCoverageIndex coverage(String className, String methodName) {
        ClassCoverageIndex index = new ClassCoverageIndex();
        index.setId("coverage-file");
        index.setClassName(className);
        index.setDisplayName("DetailController.java");
        index.setSourcePath("src/main/java/com/example/DetailController.java");
        ClassCoverageIndex.MethodCoverageDetail method = new ClassCoverageIndex.MethodCoverageDetail();
        method.setClassName(className);
        method.setMethodName(methodName);
        method.setMethodDesc("()V");
        method.setStartLine(78);
        method.setCovered(true);
        index.setMethods(List.of(method));
        return index;
    }

    private VerificationModels.Baseline baseline() {
        return new VerificationModels.Baseline(BASELINE, PROJECT, "baseline", "repo", "commit", "app", null, COVERAGE, null,
                null, null, null, "v1", VerificationModels.BaselineStatus.COMPLETED,
                VerificationModels.Freshness.LIVE, "test", LocalDateTime.now(), LocalDateTime.now());
    }
}
