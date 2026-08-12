package com.oAT.web.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ovanth.client.OvanthDraftClient;
import com.oAT.web.control.entity.ResultNotified;
import com.oAT.web.service.ProjectService;
import com.oAT.web.service.entity.ProjectVo;
import com.oAT.web.service.entity.UserVo;
import com.oAT.web.verification.VerificationService;
import com.oAT.web.verification.model.VerificationModels.AssetSnapshot;
import com.oAT.web.verification.model.VerificationModels.AssetType;
import com.oAT.web.verification.model.VerificationModels.SourceType;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/projects/{projectId}/ai")
public class AiDraftProxyController {
    private static final String OAT_AI_SYSTEM_PROMPT = """
            你是 ourAccurateTest 的企业级 AI 分析助手。你只基于输入资料做解析、抽取、评估和建议，不能编造不存在的需求、用例、缺陷、源码、覆盖率、版本或执行事实。
            输出必须是严格 JSON，不要 Markdown，不要解释文本。统一结构：
            {
              "summary": "一句话结论",
              "status": "WAITING_HUMAN_REVIEW",
              "items": [],
              "risks": [],
              "recommendedActions": [],
              "confidence": 0.0,
              "aiGenerated": true
            }
            items/risks/recommendedActions 必须引用输入中真实存在的定位或字段；证据不足时降低 confidence 并说明需要补充的依据。
            """;

    private final OvanthDraftClient aiDraftClient;
    private final VerificationService verificationService;
    private final ProjectService projectService;
    private final ObjectMapper objectMapper;

    public AiDraftProxyController(OvanthDraftClient aiDraftClient,
                                  VerificationService verificationService,
                                  ProjectService projectService,
                                  ObjectMapper objectMapper) {
        this.aiDraftClient = aiDraftClient;
        this.verificationService = verificationService;
        this.projectService = projectService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/tasks")
    public ResultNotified<OvanthDraftClient.AiTaskSubmissionResponse> submitTask(@PathVariable String projectId,
                                                                              @RequestBody AiTaskSubmitRequest request) {
        Map<String, Object> context = new LinkedHashMap<>(request.context() == null ? Map.of() : request.context());
        context.putIfAbsent("projectId", projectId);
        return ok("AI任务已提交", aiDraftClient.submit("oAT", request.intent(), context));
    }

    /**
     * 导入区「AI 生成草稿」入口：无需先有资产，用户粘贴的文本直接作为 AI 任务上下文。
     * 草稿确认后由 {@link #confirm} 落库为新的 AI 生成资产（SourceType.AGENT, aiGenerated=true）。
     */
    @PostMapping("/generate")
    public ResultNotified<OvanthDraftClient.AiTaskSubmissionResponse> generate(@PathVariable String projectId,
                                                                           @RequestBody AiGenerateRequest request) {
        Assert.hasText(request.intent(), "intent 不能为空");
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("projectId", projectId);
        context.put("title", title(request.intent()));
        context.put("system", OAT_AI_SYSTEM_PROMPT);
        context.put("user", userPrompt(request.intent(), assetTypeName(request.intent()), title(request.intent())));
        if (StringUtils.hasText(request.content())) {
            context.put("input", Map.of("projectId", projectId, "sourceText", request.content()));
        }
        return ok("AI草稿生成任务已提交", aiDraftClient.submit("oAT", request.intent(), context));
    }

    @PostMapping("/requirements/{assetId}/parse")
    public ResultNotified<OvanthDraftClient.AiTaskSubmissionResponse> parseRequirement(@PathVariable String projectId,
                                                                                    @PathVariable String assetId) {
        return submitAssetTask(projectId, assetId, "requirement.parse", "需求解析任务已提交");
    }

    @PostMapping("/testcases/{assetId}/parse")
    public ResultNotified<OvanthDraftClient.AiTaskSubmissionResponse> parseTestcase(@PathVariable String projectId,
                                                                                 @PathVariable String assetId) {
        return submitAssetTask(projectId, assetId, "testcase.parse", "用例解析任务已提交");
    }

    @PostMapping("/defects/{assetId}/parse")
    public ResultNotified<OvanthDraftClient.AiTaskSubmissionResponse> parseDefect(@PathVariable String projectId,
                                                                               @PathVariable String assetId) {
        return submitAssetTask(projectId, assetId, "defect.parse", "缺陷解析任务已提交");
    }

    @PostMapping("/coverage/{assetId}/analyze")
    public ResultNotified<OvanthDraftClient.AiTaskSubmissionResponse> analyzeCoverage(@PathVariable String projectId,
                                                                                   @PathVariable String assetId) {
        return submitAssetTask(projectId, assetId, "coverage.analyze", "覆盖率分析任务已提交");
    }

    @PostMapping("/sources/{assetId}/analyze")
    public ResultNotified<OvanthDraftClient.AiTaskSubmissionResponse> analyzeSource(@PathVariable String projectId,
                                                                                 @PathVariable String assetId) {
        return submitAssetTask(projectId, assetId, "source.analyze", "源码分析任务已提交");
    }

    @PostMapping("/git/{assetId}/analyze")
    public ResultNotified<OvanthDraftClient.AiTaskSubmissionResponse> analyzeGit(@PathVariable String projectId,
                                                                              @PathVariable String assetId) {
        return submitAssetTask(projectId, assetId, "git.analyze", "Git影响分析任务已提交");
    }

    @PostMapping("/versions/{assetId}/analyze")
    public ResultNotified<OvanthDraftClient.AiTaskSubmissionResponse> analyzeVersion(@PathVariable String projectId,
                                                                                  @PathVariable String assetId) {
        return submitAssetTask(projectId, assetId, "version.analyze", "版本影响分析任务已提交");
    }

    @GetMapping("/tasks/{taskId}/draft")
    public ResultNotified<Map<String, Object>> getDraft(@PathVariable String projectId, @PathVariable String taskId) {
        JsonNode draft = aiDraftClient.getDraft(taskId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("taskId", draft == null ? taskId : draft.path("taskId").asText(taskId));
        response.put("status", draft == null ? "UNKNOWN" : draft.path("status").asText("UNKNOWN"));
        response.put("payload", parseJsonOrText(draft == null ? null : draft.path("payload").asText(null)));
        response.put("error", draft == null ? "" : draft.path("error").asText(""));
        response.put("aiGenerated", draft != null && draft.path("aiGenerated").asBoolean(false));
        return ok("获取AI草稿成功", response);
    }

    @PostMapping("/tasks/{taskId}/confirm")
    public ResultNotified<Object> confirm(@PathVariable String projectId,
                                          @PathVariable String taskId,
                                          @SessionAttribute UserVo user,
                                          @RequestBody ConfirmRequest request) {
        ProjectVo project = projectService.getProjectByProjectIdAndMemberId(projectId, user.getId());
        Assert.notNull(project, "找不到指定项目,或者您没有该项目的访问权限");
        if (!request.confirmed()) {
            aiDraftClient.confirm(taskId, false);
            return ok("AI草稿已拒绝", taskId);
        }

        OvanthDraftClient.AiTaskRecord task = aiDraftClient.getTask(taskId);
        Assert.notNull(task, "找不到指定AI任务");
        Map<String, Object> context = parseObject(task.context());
        Assert.isTrue(projectId.equals(String.valueOf(context.get("projectId"))), "AI任务不属于当前项目");
        AssetType assetType = assetType(task.intent());
        String content = StringUtils.hasText(request.payload()) ? request.payload() : task.payload();
        Assert.hasText(content, "AI草稿内容不能为空");

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("aiTaskId", taskId);
        metadata.put("aiIntent", task.intent());
        metadata.put("aiConfirmed", true);
        String assetId = text(context.get("assetId"));
        AssetSnapshot imported;
        if (StringUtils.hasText(assetId)) {
            // 资产级 AI：关联来源资产，继承其版本
            metadata.put("aiSourceAssetId", assetId);
            AssetSnapshot source = verificationService.asset(projectId, assetId, assetType);
            imported = verificationService.importAsset(projectId, user.getId(), assetType,
                    SourceType.AGENT, "ai-draft-" + taskId + ".json", content,
                    assetId, null, source == null ? null : source.sourceVersion(), metadata, true);
        } else {
            // 导入区 AI 生成：无来源资产，直接新建（sourceText 已随草稿内容落库）
            imported = verificationService.importAsset(projectId, user.getId(), assetType,
                    SourceType.AGENT, "ai-draft-" + taskId + ".json", content,
                    null, null, null, metadata, true);
        }
        aiDraftClient.confirm(taskId, true);
        return ok("AI草稿已确认并落库", imported);
    }

    private ResultNotified<OvanthDraftClient.AiTaskSubmissionResponse> submitAssetTask(String projectId,
                                                                                    String assetId,
                                                                                    String intent,
                                                                                    String message) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("projectId", projectId);
        context.put("assetId", assetId);
        AssetType assetType = assetType(intent);
        context.put("title", title(intent));
        context.put("system", OAT_AI_SYSTEM_PROMPT);
        context.put("user", userPrompt(intent, assetType.name(), title(intent)));
        context.put("input", Map.of("projectId", projectId, "assetId", assetId, "assetType", assetType.name()));
        context.put("toolRequest", Map.of(
                "method", "POST",
                "path", "/api/ai-tools/assets/read",
                "body", Map.of("projectId", projectId, "assetId", assetId, "assetType", assetType.name())));
        return ok(message, aiDraftClient.submit("oAT", intent, context));
    }

    private String title(String intent) {
        if ("requirement.parse".equals(intent)) return "需求解析草稿";
        if ("testcase.parse".equals(intent)) return "用例解析草稿";
        if ("defect.parse".equals(intent)) return "缺陷解析草稿";
        if ("coverage.analyze".equals(intent)) return "覆盖率缺口分析草稿";
        if ("source.analyze".equals(intent)) return "源码分析草稿";
        if ("git.analyze".equals(intent)) return "Git影响分析草稿";
        if ("version.analyze".equals(intent)) return "版本影响分析草稿";
        return "AI草稿";
    }

    private String assetTypeName(String intent) {
        try {
            return assetType(intent).name();
        } catch (IllegalArgumentException ignored) {
            return "TEXT";
        }
    }

    private String userPrompt(String intent, String assetType, String title) {
        return "任务: " + intent + "\n"
                + "资产类型: " + assetType + "\n"
                + "草稿标题: " + title + "\n"
                + "请结合任务类型输出可人工确认的业务草稿。";
    }

    private Map<String, Object> parseObject(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("AI任务上下文不是合法JSON", e);
        }
    }

    private Object parseJsonOrText(String value) {
        if (!StringUtils.hasText(value)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, Object.class);
        } catch (Exception ignored) {
            return value;
        }
    }

    private AssetType assetType(String intent) {
        if ("requirement.parse".equals(intent)) return AssetType.REQUIREMENT;
        if ("testcase.parse".equals(intent)) return AssetType.TESTCASE;
        if ("defect.parse".equals(intent)) return AssetType.DEFECT;
        if ("coverage.analyze".equals(intent)) return AssetType.COVERAGE;
        if ("source.analyze".equals(intent) || "git.analyze".equals(intent)) return AssetType.SOURCE;
        if ("version.analyze".equals(intent)) return AssetType.EXECUTION;
        throw new IllegalArgumentException("不支持确认落库的AI任务类型: " + intent);
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private <T> ResultNotified<T> ok(String message, T data) {
        ResultNotified<T> result = new ResultNotified<>(true, message, data);
        return result;
    }

    public record AiTaskSubmitRequest(String intent, Map<String, Object> context) {
    }

    public record AiGenerateRequest(String intent, String content) {
    }

    public record ConfirmRequest(boolean confirmed, String payload) {
    }
}
