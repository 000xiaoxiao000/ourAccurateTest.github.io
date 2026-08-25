package com.oAT.web.ai;

import com.oAT.web.verification.VerificationService;
import com.oAT.web.verification.graph.GraphService;
import com.oAT.web.verification.graph.IncrementalRecomputeService;
import com.oAT.web.verification.graph.TestExecutionProjectionService;
import com.oAT.web.verification.model.VerificationModels.AssetType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/ai-tools")
public class AiBusinessToolController {

    private static final String AI_TOOL_USER = "ai-tool-gateway";

    private final VerificationService verificationService;
    private final GraphService graphService;

    public AiBusinessToolController(VerificationService verificationService, GraphService graphService) {
        this.verificationService = verificationService;
        this.graphService = graphService;
    }

    @GetMapping
    public List<Map<String, Object>> listTools() {
        return Stream.of(
                tool("asset.read", "Read any verification asset by projectId, assetId and assetType.", "/api/ai-tools/assets/read"),
                tool("requirement.readAsset", "Read a requirement asset.", "/api/ai-tools/requirement/read-asset"),
                tool("testcase.readAsset", "Read a testcase asset.", "/api/ai-tools/testcase/read-asset"),
                tool("defect.readAsset", "Read a defect asset.", "/api/ai-tools/defect/read-asset"),
                tool("coverage.readAsset", "Read a coverage asset.", "/api/ai-tools/coverage/read-asset"),
                tool("source.readAsset", "Read a source/Git snapshot asset.", "/api/ai-tools/source/read-asset"),
                tool("execution.readAsset", "Read an execution/version evidence asset.", "/api/ai-tools/execution/read-asset"),
                tool("writePatch", "向源码资产提交补丁（写操作，需 X-AI-TOOL-TOKEN）", "/api/ai-tools/write-patch"),
                tool("tests.run", "触发测试执行投影（长任务，需 X-AI-TOOL-TOKEN）", "/api/ai-tools/tests/run"),
                tool("coverage.recompute", "增量覆盖率影响面重算（长任务，需 X-AI-TOOL-TOKEN）", "/api/ai-tools/coverage/recompute"))
                .toList();
    }

    @PostMapping("/assets/read")
    public VerificationService.AssetContentForTool readAsset(@RequestBody ReadAssetRequest request) {
        return verificationService.loadAssetContentForTool(request.projectId(), request.assetId(), parseAssetType(request.assetType()));
    }

    @PostMapping("/requirement/read-asset")
    public VerificationService.AssetContentForTool readRequirementAsset(@RequestBody ReadAssetRequest request) {
        return readTypedAsset(request, AssetType.REQUIREMENT);
    }

    @PostMapping("/testcase/read-asset")
    public VerificationService.AssetContentForTool readTestcaseAsset(@RequestBody ReadAssetRequest request) {
        return readTypedAsset(request, AssetType.TESTCASE);
    }

    @PostMapping("/defect/read-asset")
    public VerificationService.AssetContentForTool readDefectAsset(@RequestBody ReadAssetRequest request) {
        return readTypedAsset(request, AssetType.DEFECT);
    }

    @PostMapping("/coverage/read-asset")
    public VerificationService.AssetContentForTool readCoverageAsset(@RequestBody ReadAssetRequest request) {
        return readTypedAsset(request, AssetType.COVERAGE);
    }

    @PostMapping("/source/read-asset")
    public VerificationService.AssetContentForTool readSourceAsset(@RequestBody ReadAssetRequest request) {
        return readTypedAsset(request, AssetType.SOURCE);
    }

    @PostMapping("/execution/read-asset")
    public VerificationService.AssetContentForTool readExecutionAsset(@RequestBody ReadAssetRequest request) {
        return readTypedAsset(request, AssetType.EXECUTION);
    }

    /**
     * 向源码资产提交补丁（写操作）。
     * 鉴权由 {@code LoginInterceptor} 通过 {@code X-AI-TOOL-TOKEN} 统一拦截（本端点路径前缀 {@code /api/ai-tools/}）。
     * 复用既有 {@link VerificationService#updateAsset}；不在此硬编码任何写/执行逻辑。
     */
    @PostMapping("/write-patch")
    public AiToolResult writePatch(@RequestBody WritePatchRequest request) {
        if (!StringUtils.hasText(request.projectId()) || !StringUtils.hasText(request.assetId())
                || !StringUtils.hasText(request.patch())) {
            return AiToolResult.fail("projectId、assetId、patch 均为必填");
        }
        String oldContent = verificationService.assetRawContent(request.projectId(), request.assetId());

        String newContent;
        boolean replace = request.replaceEntireContent() != null && request.replaceEntireContent();
        if (replace) {
            newContent = request.patch();
        } else {
            try {
                newContent = PatchApplier.apply(oldContent, request.patch());
            } catch (IllegalArgumentException e) {
                return AiToolResult.fail("补丁解析失败，请确认是否为 unified diff，或设置 replaceEntireContent=true：" + e.getMessage());
            }
        }

        try {
            var updated = verificationService.updateAsset(request.projectId(), request.assetId(),
                    AI_TOOL_USER, new VerificationService.UpdateAsset(null, newContent, null, null, null));
            boolean reprojected = false;
            if (StringUtils.hasText(request.baselineId())) {
                try {
                    graphService.projectStatic(request.projectId(), request.baselineId());
                    reprojected = true;
                } catch (RuntimeException ignored) {
                    // 基线未绑定源码工程等情况：补丁已落库，重投影为 best-effort
                }
            }
            return AiToolResult.ok("补丁已应用并创建新资产版本", Map.of(
                    "assetId", updated.id(),
                    "applied", true,
                    "reprojected", reprojected));
        } catch (RuntimeException e) {
            return AiToolResult.fail("写入资产失败：" + e.getMessage());
        }
    }

    /**
     * 触发测试执行投影（oAT 不直跑测试套件，复用既有投影能力）。
     */
    @PostMapping("/tests/run")
    public AiToolResult runTests(@RequestBody RunTestsRequest request) {
        if (!StringUtils.hasText(request.projectId()) || !StringUtils.hasText(request.baselineId())) {
            return AiToolResult.fail("projectId、baselineId 均为必填");
        }
        String scope = StringUtils.hasText(request.scope()) ? request.scope() : "all";
        try {
            TestExecutionProjectionService.ProjectionResult result =
                    graphService.projectTestExecutions(request.projectId(), request.baselineId());
            return AiToolResult.ok("测试执行投影已完成", Map.of(
                    "projectId", request.projectId(),
                    "baselineId", request.baselineId(),
                    "scope", scope,
                    "nodeCount", result.nodeCount(),
                    "status", "DONE"));
        } catch (RuntimeException e) {
            return AiToolResult.fail("测试执行投影失败：" + e.getMessage());
        }
    }

    /**
     * 增量覆盖率影响面重算（写/执行类，长任务）。
     */
    @PostMapping("/coverage/recompute")
    public AiToolResult recomputeCoverage(@RequestBody RecomputeCoverageRequest request) {
        if (!StringUtils.hasText(request.projectId()) || !StringUtils.hasText(request.baselineId())) {
            return AiToolResult.fail("projectId、baselineId 均为必填");
        }
        if (request.changedSymbols() == null || request.changedSymbols().isEmpty()) {
            return AiToolResult.fail("changedSymbols 不能为空");
        }
        try {
            IncrementalRecomputeService.RecomputeResult result =
                    graphService.incrementalRecompute(request.projectId(), request.baselineId(), request.changedSymbols());
            return AiToolResult.ok("已提交增量重算任务", Map.of(
                    "affectedSymbols", result.affectedSymbols(),
                    "affectedNodeCount", result.affectedNodeCount(),
                    "affectedAcIds", result.affectedAcIds(),
                    "affectedTestcaseIds", result.affectedTestcaseIds(),
                    "staleAggregates", result.staleAggregates()));
        } catch (RuntimeException e) {
            return AiToolResult.fail("增量重算失败：" + e.getMessage());
        }
    }

    @GetMapping("/projects/{projectId}/requirements/{assetId}")
    public VerificationService.AssetContentForTool readRequirementAssetByPath(@PathVariable String projectId, @PathVariable String assetId) {
        return verificationService.loadAssetContentForTool(projectId, assetId, AssetType.REQUIREMENT);
    }

    private VerificationService.AssetContentForTool readTypedAsset(ReadAssetRequest request, AssetType assetType) {
        return verificationService.loadAssetContentForTool(request.projectId(), request.assetId(), assetType);
    }

    private AssetType parseAssetType(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("assetType is required");
        }
        return AssetType.valueOf(value.trim().toUpperCase());
    }

    private Map<String, Object> tool(String name, String description, String path) {
        return Map.of("name", name, "description", description, "method", "POST", "path", path);
    }

    public record ReadAssetRequest(String projectId, String assetId, String assetType) {
    }
}
