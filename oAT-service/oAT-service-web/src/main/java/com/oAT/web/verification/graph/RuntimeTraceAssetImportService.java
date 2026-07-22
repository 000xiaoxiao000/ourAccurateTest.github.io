package com.oAT.web.verification.graph;

import com.oAT.web.common.UtilJson;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Optional;

@Service
public class RuntimeTraceAssetImportService {
    private final GraphService graphService;

    public RuntimeTraceAssetImportService(GraphService graphService) {
        this.graphService = graphService;
    }

    public Optional<RuntimeTraceProjectionService.ProjectionResult> importIfStructuredTrace(String projectId, String baselineId,
                                                                                              String content) {
        if (!StringUtils.hasText(content) || !content.trim().startsWith("{")) {
            return Optional.empty();
        }
        try {
            RuntimeTraceProjectionService.RuntimeTraceBatch batch = UtilJson.getObjectMapper()
                    .readValue(content, RuntimeTraceProjectionService.RuntimeTraceBatch.class);
            if (batch.calls() == null || batch.calls().isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(graphService.projectRuntimeTrace(projectId, baselineId, batch));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }
}
