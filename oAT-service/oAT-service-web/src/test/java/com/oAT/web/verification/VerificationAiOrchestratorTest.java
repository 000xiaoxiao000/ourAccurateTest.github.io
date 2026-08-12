package com.oAT.web.verification;

import com.aiplatform.client.AiDraftClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oAT.web.persistence.entity.StaticSourceClassInfo;
import com.oAT.web.persistence.entity.StaticSourceInfo;
import com.oAT.web.persistence.entity.StaticSourceMethodInfo;
import com.oAT.web.verification.VerificationAiOrchestrator.AiVerificationInput;
import com.oAT.web.verification.VerificationAiOrchestrator.AiVerificationResult;
import com.oAT.web.verification.model.VerificationModels.TraceLink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VerificationAiOrchestratorTest {

    private AiDraftClient aiDraftClient;
    private VerificationAiOrchestrator orchestrator;

    private static final String MINIMAL_JSON = """
            {"criteria":[{"requirementKey":"REQ-1","acKey":"AC-1","title":"T","content":"验收内容","confidence":0.9}],
             "testcases":[],
             "traceLinks":[{"sourceKey":"AC-1","targetType":"SOURCE_SYMBOL","targetKey":"com.example.OrderService#create","confidence":0.85}],
             "findings":[{"acKey":"AC-1","findingType":"SATISFIED_SUMMARY","perspective":"CROSS","severity":"INFO","title":"已满足","description":"依据充分","verdict":"SATISFIED","confidence":0.9}]}
            """;

    @BeforeEach
    void setUp() {
        aiDraftClient = mock(AiDraftClient.class);
        // 默认认为 ai-platform 健康可用（真实环境中 AiGateway.isAvailable() 会做健康探测）
        when(aiDraftClient.ping()).thenReturn(true);
        orchestrator = new VerificationAiOrchestrator(new AiGateway(aiDraftClient));
    }

    // ── Prompt cache ──────────────────────────────────────────────────────────

    @Test
    void same_input_hits_cache_and_calls_llm_only_once() {
        when(aiDraftClient.execute(anyString(), anyString(), anyMap())).thenReturn(aiResponse(MINIMAL_JSON));

        AiVerificationInput input = simpleInput("baseline-1");
        orchestrator.analyze(input);
        orchestrator.analyze(input);

        verify(aiDraftClient, times(1)).execute(anyString(), anyString(), anyMap());
    }

    @Test
    void different_baseline_id_bypasses_cache() {
        when(aiDraftClient.execute(anyString(), anyString(), anyMap())).thenReturn(aiResponse(MINIMAL_JSON));

        orchestrator.analyze(simpleInput("bl-A"));
        orchestrator.analyze(simpleInput("bl-B"));

        verify(aiDraftClient, times(2)).execute(anyString(), anyString(), anyMap());
    }

    @Test
    void different_requirement_content_bypasses_cache() {
        when(aiDraftClient.execute(anyString(), anyString(), anyMap())).thenReturn(aiResponse(MINIMAL_JSON));

        orchestrator.analyze(inputWithRequirement("baseline-1", "REQ-1: 用户可以下单"));
        orchestrator.analyze(inputWithRequirement("baseline-1", "REQ-2: 用户可以退单"));

        verify(aiDraftClient, times(2)).execute(anyString(), anyString(), anyMap());
    }

    // ── Minimal subgraph context ──────────────────────────────────────────────

    @Test
    void relevant_class_appears_in_prompt_before_irrelevant_one() {
        AtomicInteger capturedPromptRelevantPos = new AtomicInteger(-1);
        AtomicInteger capturedPromptIrrelevantPos = new AtomicInteger(-1);

        when(aiDraftClient.execute(anyString(), anyString(), argThat(context -> {
            String prompt = String.valueOf(context.get("user"));
            capturedPromptRelevantPos.set(prompt.indexOf("OrderService"));
            capturedPromptIrrelevantPos.set(prompt.indexOf("UnrelatedHelper"));
            return true;
        }))).thenReturn(aiResponse(MINIMAL_JSON));

        // requirement text mentions "order" — OrderService should rank higher than UnrelatedHelper
        AiVerificationInput input = new AiVerificationInput(
                "bl-1",
                "REQ-1: 用户提交 order 订单后系统应创建订单记录",
                null, null, null, null, null,
                List.of(
                        sourceWith("com.example.UnrelatedHelper", "doSomething"),
                        sourceWith("com.example.OrderService", "create")));
        orchestrator.analyze(input);

        int relPos = capturedPromptRelevantPos.get();
        int irrPos = capturedPromptIrrelevantPos.get();
        // Both classes must appear (budget allows) and OrderService must come first
        assertTrue(relPos >= 0, "OrderService must appear in the prompt");
        assertTrue(irrPos < 0 || relPos < irrPos,
                "OrderService should appear before UnrelatedHelper (relPos=" + relPos + " irrPos=" + irrPos + ")");
    }

    // ── Hallucination guard ───────────────────────────────────────────────────

    @Test
    void hallucinated_source_symbol_is_stripped_from_trace_links() {
        // LLM returns a SOURCE_SYMBOL ref to "com.example.FakeService#nonexistent"
        // which does not exist in the input static sources
        String jsonWithFakeRef = """
                {"criteria":[{"requirementKey":"REQ-1","acKey":"AC-1","title":"T","content":"C","confidence":0.8}],
                 "testcases":[],
                 "traceLinks":[
                   {"sourceKey":"AC-1","targetType":"SOURCE_SYMBOL","targetKey":"com.example.FakeService#nonexistent","confidence":0.9},
                   {"sourceKey":"AC-1","targetType":"SOURCE_SYMBOL","targetKey":"com.example.OrderService#create","confidence":0.85}
                 ],
                 "findings":[{"acKey":"AC-1","findingType":"PARTIAL","perspective":"CROSS","severity":"MEDIUM","title":"部分","description":"desc","verdict":"PARTIAL","confidence":0.7}]}
                """;
        when(aiDraftClient.execute(anyString(), anyString(), anyMap())).thenReturn(aiResponse(jsonWithFakeRef));

        AiVerificationInput input = new AiVerificationInput(
                "bl-1", "REQ-1: create order", null, null, null, null, null,
                List.of(sourceWith("com.example.OrderService", "create")));

        AiVerificationResult result = orchestrator.analyze(input);

        List<TraceLink> sourceLinks = result.traceLinks().stream()
                .filter(l -> "SOURCE_SYMBOL".equals(l.targetType())).toList();
        // FakeService link should be stripped, OrderService link should remain
        assertTrue(sourceLinks.stream().noneMatch(l -> l.targetId().contains("FakeService")),
                "Hallucinated FakeService ref must be stripped");
        assertTrue(sourceLinks.stream().anyMatch(l -> l.targetId().contains("OrderService")),
                "Valid OrderService ref must be kept");
    }

    @Test
    void non_source_symbol_trace_links_are_never_stripped() {
        // TESTCASE and EXECUTION links are opaque keys — never stripped regardless of content
        String jsonWithTestcaseLink = """
                {"criteria":[{"requirementKey":"REQ-1","acKey":"AC-1","title":"T","content":"C","confidence":0.8}],
                 "testcases":[{"externalKey":"TC-001","title":"Test","steps":"step","expected":"ok"}],
                 "traceLinks":[
                   {"sourceKey":"AC-1","targetType":"TESTCASE","targetKey":"TC-001","confidence":0.9},
                   {"sourceKey":"AC-1","targetType":"EXECUTION","targetKey":"run-2024","confidence":0.7}
                 ],
                 "findings":[{"acKey":"AC-1","findingType":"PARTIAL","perspective":"TEST","severity":"MEDIUM","title":"T","description":"D","verdict":"PARTIAL","confidence":0.7}]}
                """;
        when(aiDraftClient.execute(anyString(), anyString(), anyMap())).thenReturn(aiResponse(jsonWithTestcaseLink));

        AiVerificationResult result = orchestrator.analyze(simpleInput("bl-1"));

        long testcaseLinks = result.traceLinks().stream().filter(l -> "TESTCASE".equals(l.targetType())).count();
        long executionLinks = result.traceLinks().stream().filter(l -> "EXECUTION".equals(l.targetType())).count();
        assertEquals(1, testcaseLinks, "TESTCASE link must not be stripped");
        assertEquals(1, executionLinks, "EXECUTION link must not be stripped");
    }

    @Test
    void valid_source_symbol_from_static_index_is_kept() {
        when(aiDraftClient.execute(anyString(), anyString(), anyMap())).thenReturn(aiResponse(MINIMAL_JSON));

        AiVerificationResult result = orchestrator.analyze(new AiVerificationInput(
                "bl-1", "REQ-1: create order", null, null, null, null, null,
                List.of(sourceWith("com.example.OrderService", "create"))));

        // com.example.OrderService#create is in input — the link must survive
        assertTrue(result.traceLinks().stream()
                .anyMatch(l -> "SOURCE_SYMBOL".equals(l.targetType())
                        && l.targetId().contains("OrderService")));
    }

    @Test
    void ai_platform_failure_throws_immediately() {
        when(aiDraftClient.execute(anyString(), anyString(), anyMap())).thenThrow(new IllegalStateException("ai-platform unavailable"));
        assertThrows(IllegalStateException.class, () -> orchestrator.analyze(simpleInput("bl-1")));
        verify(aiDraftClient, times(1)).execute(anyString(), anyString(), anyMap());
    }

    @Test
    void nested_requirement_acceptance_criteria_are_parsed() {
        String nestedJson = """
                {"requirements":[
                  {"requirementKey":"REQ-9","title":"登录",
                   "acceptanceCriteria":["用户必须可以使用正确账号密码登录系统"]}
                ],"testcases":[],"traceLinks":[],"findings":[]}
                """;
        when(aiDraftClient.execute(anyString(), anyString(), anyMap())).thenReturn(aiResponse(nestedJson));

        AiVerificationResult result = orchestrator.analyze(inputWithRequirement("bl-1", "登录需求"));

        assertEquals(1, result.criteria().size());
        assertEquals("REQ-9", result.criteria().get(0).requirementKey());
        assertEquals("用户必须可以使用正确账号密码登录系统", result.criteria().get(0).content());
    }

    @Test
    void fallback_extracts_plain_text_requirement_when_ai_has_no_criteria() {
        when(aiDraftClient.execute(anyString(), anyString(), anyMap())).thenReturn(aiResponse("{}"), aiResponse("{}"));

        AiVerificationResult result = orchestrator.analyze(inputWithRequirement("bl-1",
                "用户登录验收：系统必须支持用户使用正确账号密码登录，登录成功后进入首页。"));

        assertEquals(1, result.criteria().size());
        assertTrue(result.criteria().get(0).content().contains("系统必须支持用户使用正确账号密码登录"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private JsonNode aiResponse(String text) {
        ObjectNode node = new ObjectMapper().createObjectNode();
        node.put("text", text);
        return node;
    }

    private AiVerificationInput simpleInput(String baselineId) {
        return new AiVerificationInput(baselineId, "REQ-1: 简单需求", null, null, null, null, null, List.of());
    }

    private AiVerificationInput inputWithRequirement(String baselineId, String requirement) {
        return new AiVerificationInput(baselineId, requirement, null, null, null, null, null, List.of());
    }

    private StaticSourceInfo sourceWith(String className, String methodName) {
        StaticSourceMethodInfo method = new StaticSourceMethodInfo();
        method.setMethodName(methodName);
        method.setMethodDesc("()V");
        method.setMethodLineNumberMap(List.of(10));
        method.setInvocations(new ArrayList<>());

        StaticSourceClassInfo classInfo = new StaticSourceClassInfo();
        classInfo.setClassName(className);
        classInfo.setMethodMaps(Map.of(methodName, method));
        classInfo.setSourceCode("public class "
                + className.substring(className.lastIndexOf('.') + 1)
                + " { public void " + methodName + "() {} }");

        StaticSourceInfo info = new StaticSourceInfo(classInfo);
        info.setAppId("app-1");
        return info;
    }
}
