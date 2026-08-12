package com.oAT.web.ai;

import com.oAT.web.verification.VerificationService;
import com.oAT.web.verification.model.VerificationModels.AssetType;
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

    private final VerificationService verificationService;

    public AiBusinessToolController(VerificationService verificationService) {
        this.verificationService = verificationService;
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
                tool("execution.readAsset", "Read an execution/version evidence asset.", "/api/ai-tools/execution/read-asset"))
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
