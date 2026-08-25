package com.oAT.web.ai;

import com.oAT.web.verification.VerificationService;
import com.oAT.web.verification.model.VerificationModels.AssetSnapshot;
import com.oAT.web.verification.graph.GraphService;
import com.oAT.web.verification.graph.IncrementalRecomputeService;
import com.oAT.web.verification.graph.TestExecutionProjectionService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiBusinessToolControllerTest {

    private final VerificationService verificationService = mock(VerificationService.class);
    private final GraphService graphService = mock(GraphService.class);
    private final AiBusinessToolController controller =
            new AiBusinessToolController(verificationService, graphService);

    @Test
    void writePatchRejectsMissingFields() {
        AiToolResult result = controller.writePatch(new WritePatchRequest(null, null, null, null, true));
        assertFalse(result.success());
    }

    @Test
    void writePatchReplaceEntireContentUpdatesAsset() {
        when(verificationService.assetRawContent(any(), any())).thenReturn("old-content");

        AssetSnapshot updated = mock(AssetSnapshot.class);
        when(updated.id()).thenReturn("asset-1");
        when(verificationService.updateAsset(any(), any(), any(), any())).thenReturn(updated);

        AiToolResult result = controller.writePatch(
                new WritePatchRequest("p1", "asset-1", null, "new-content", true));

        assertTrue(result.success());
        assertEquals("asset-1", result.data().get("assetId"));
        assertEquals(true, result.data().get("applied"));
        assertEquals(false, result.data().get("reprojected"));
    }

    @Test
    void runTestsRejectsMissingBaseline() {
        AiToolResult result = controller.runTests(new RunTestsRequest("p1", null, "all"));
        assertFalse(result.success());
    }

    @Test
    void runTestsProjectsExecutions() {
        when(graphService.projectTestExecutions(anyString(), anyString()))
                .thenReturn(new TestExecutionProjectionService.ProjectionResult("snap-1", 7, 3));

        AiToolResult result = controller.runTests(new RunTestsRequest("p1", "b1", null));

        assertTrue(result.success());
        assertEquals(7, result.data().get("nodeCount"));
        assertEquals("DONE", result.data().get("status"));
        assertEquals("all", result.data().get("scope"));
    }

    @Test
    void recomputeRejectsEmptySymbols() {
        AiToolResult result = controller.recomputeCoverage(
                new RecomputeCoverageRequest("p1", "b1", List.of()));
        assertFalse(result.success());
    }

    @Test
    void recomputeInvokesIncrementalRecompute() {
        when(graphService.incrementalRecompute(anyString(), anyString(), anyList()))
                .thenReturn(new IncrementalRecomputeService.RecomputeResult(
                        List.of("com.x.Foo.bar"), 9, List.of("ac-1"), List.of("tc-1"), 2));

        AiToolResult result = controller.recomputeCoverage(
                new RecomputeCoverageRequest("p1", "b1", List.of("com.x.Foo.bar")));

        assertTrue(result.success());
        assertEquals(9, result.data().get("affectedNodeCount"));
        assertEquals(2, result.data().get("staleAggregates"));
        @SuppressWarnings("unchecked")
        List<String> acs = (List<String>) result.data().get("affectedAcIds");
        assertEquals(List.of("ac-1"), acs);
    }
}
