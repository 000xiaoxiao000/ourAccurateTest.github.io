package com.oAT.web.verification;

import com.oAT.ai.service.LLMService;
import com.oAT.web.verification.VerificationAiOrchestrator.AiVerificationInput;
import com.oAT.web.verification.VerificationAiOrchestrator.AiVerificationResult;
import com.oAT.web.verification.model.VerificationModels.EvidenceLevel;
import com.oAT.web.verification.model.VerificationModels.Severity;
import com.oAT.web.verification.model.VerificationModels.Verdict;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VerificationAiOrchestratorTest {
    @Test
    void parsesAiJsonAndMapsTraceLinksToPersistedIds() {
        LLMService llmService = mock(LLMService.class);
        when(llmService.isAvailable()).thenReturn(true);
        when(llmService.chat(anyString(), anyString())).thenReturn("""
                {
                  "criteria": [{
                    "requirementKey": "REQ-1",
                    "acKey": "AC-1",
                    "title": "登录成功",
                    "content": "用户输入正确账号密码后应登录成功",
                    "sourceLocator": "需求:1",
                    "priority": "HIGH",
                    "testable": true,
                    "ambiguity": false,
                    "confidence": 0.91
                  }],
                  "testcases": [{
                    "externalKey": "TC-1",
                    "title": "正确账号密码登录成功",
                    "steps": "输入正确账号密码并提交",
                    "expected": "登录成功",
                    "sourceLocator": "用例:1"
                  }],
                  "traceLinks": [{
                    "sourceKey": "AC-1",
                    "targetType": "TESTCASE",
                    "targetKey": "TC-1",
                    "relationType": "VERIFIED_BY",
                    "confidence": 0.88,
                    "evidenceLevel": "E1",
                    "evidence": {"reason": "用例预期覆盖该验收标准"}
                  }],
                  "findings": [{
                    "acKey": "AC-1",
                    "findingType": "MISSING_IMPLEMENTATION",
                    "perspective": "开发",
                    "severity": "HIGH",
                    "title": "缺少源码证据",
                    "description": "未提供源码或静态源码索引",
                    "suggestion": "导入源码后重新分析",
                    "confidence": 0.76,
                    "evidenceLevel": "E0",
                    "verdict": "NOT_VERIFIABLE",
                    "evidence": [{"type": "REQUIREMENT", "id": "REQ-1"}]
                  }]
                }
                """);

        VerificationAiOrchestrator orchestrator = new VerificationAiOrchestrator(llmService);
        AiVerificationResult result = orchestrator.analyze(new AiVerificationInput("base-1",
                "REQ-1 用户输入正确账号密码后应登录成功", "TC-1 正确账号密码登录成功",
                "", "", "", "", List.of()));

        assertThat(result.criteria()).hasSize(1);
        assertThat(result.testcases()).hasSize(1);
        assertThat(result.traceLinks()).hasSize(1);
        assertThat(result.traceLinks().get(0).sourceId()).isEqualTo(result.criteria().get(0).id());
        assertThat(result.traceLinks().get(0).targetId()).isEqualTo(result.testcases().get(0).id());
        assertThat(result.traceLinks().get(0).evidenceLevel()).isEqualTo(EvidenceLevel.E1);
        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().get(0).severity()).isEqualTo(Severity.HIGH);
        assertThat(result.findings().get(0).perspective()).isEqualTo(com.oAT.web.verification.model.VerificationModels.Perspective.DEVELOPMENT);
        assertThat(result.findings().get(0).verdict()).isEqualTo(Verdict.NOT_VERIFIABLE);
        assertThat(result.findings().get(0).acId()).isEqualTo(result.criteria().get(0).id());
    }

    @Test
    void acceptsWrappedJsonAliasesAndBackfillsMissingFindings() {
        LLMService llmService = mock(LLMService.class);
        when(llmService.isAvailable()).thenReturn(true);
        when(llmService.chat(anyString(), anyString())).thenReturn("""
                下面是分析结果：
                ```json
                {
                  "acceptanceCriteria": [{
                    "requirementId": "REQ-login",
                    "key": "AC-login-success",
                    "name": "登录成功",
                    "text": "正确账号密码可以登录系统"
                  }],
                  "testCases": [{
                    "id": "TC-login-success",
                    "name": "正确账号密码登录",
                    "step": "输入账号密码并提交",
                    "expectedResult": "进入首页"
                  }],
                  "trace_links": [{
                    "acKey": "AC-login-success",
                    "type": "TEST_CASE",
                    "target": "TC-login-success",
                    "relation": "VERIFIED_BY",
                    "level": "E1"
                  }]
                }
                ```
                """);

        VerificationAiOrchestrator orchestrator = new VerificationAiOrchestrator(llmService);
        AiVerificationResult result = orchestrator.analyze(new AiVerificationInput("base-1",
                "REQ-login 正确账号密码可以登录系统", "TC-login-success 正确账号密码登录",
                "", "", "", "", List.of()));

        assertThat(result.criteria()).hasSize(1);
        assertThat(result.testcases()).hasSize(1);
        assertThat(result.traceLinks()).hasSize(1);
        assertThat(result.traceLinks().get(0).targetId()).isEqualTo(result.testcases().get(0).id());
        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().get(0).findingType()).isEqualTo("MISSING_EVIDENCE");
        assertThat(result.findings().get(0).verdict()).isEqualTo(Verdict.PARTIAL);
    }

    @Test
    void acceptsJsonEmbeddedInProviderResponsePayload() {
        LLMService llmService = mock(LLMService.class);
        when(llmService.isAvailable()).thenReturn(true);
        when(llmService.chat(anyString(), anyString())).thenReturn("""
                {
                  "data": {
                    "content": "{\\"criteria\\":[{\\"id\\":\\"AC-1\\",\\"description\\":\\"用户可以提交订单\\"}],\\"cases\\":[{\\"key\\":\\"TC-1\\",\\"name\\":\\"提交订单\\",\\"actions\\":\\"提交\\",\\"expectation\\":\\"订单创建成功\\"}],\\"links\\":[{\\"from\\":\\"AC-1\\",\\"type\\":\\"TESTCASE\\",\\"to\\":\\"TC-1\\"}]}"
                  }
                }
                """);

        VerificationAiOrchestrator orchestrator = new VerificationAiOrchestrator(llmService);
        AiVerificationResult result = orchestrator.analyze(new AiVerificationInput("base-1",
                "用户可以提交订单", "提交订单", "", "", "", "", List.of()));

        assertThat(result.criteria()).hasSize(1);
        assertThat(result.testcases()).hasSize(1);
        assertThat(result.traceLinks()).hasSize(1);
        assertThat(result.findings()).hasSize(1);
    }

    @Test
    void retriesWithJsonRepairWhenFirstResponseIsInvalid() {
        LLMService llmService = mock(LLMService.class);
        when(llmService.isAvailable()).thenReturn(true);
        when(llmService.chat(anyString(), anyString())).thenReturn(
                "分析结果生成中，JSON 被截断了",
                """
                        {"criteria":[{"acKey":"AC-1","content":"用户可以登录"}]}
                        """);

        VerificationAiOrchestrator orchestrator = new VerificationAiOrchestrator(llmService);
        AiVerificationResult result = orchestrator.analyze(new AiVerificationInput("base-1",
                "用户可以登录", "", "", "", "", "", List.of()));

        assertThat(result.criteria()).hasSize(1);
        assertThat(result.findings()).hasSize(1);
        org.mockito.Mockito.verify(llmService, times(2)).chat(anyString(), anyString());
    }

    @Test
    void ignoresDeepSeekThinkingBeforeJson() {
        LLMService llmService = mock(LLMService.class);
        when(llmService.isAvailable()).thenReturn(true);
        when(llmService.chat(anyString(), anyString())).thenReturn("""
                <think>我先分析需求，示例对象 {"temporary": true}</think>
                {"criteria":[{"acKey":"AC-1","content":"用户可以登录"}]}
                """);

        AiVerificationResult result = new VerificationAiOrchestrator(llmService).analyze(
                new AiVerificationInput("base-1", "用户可以登录", "", "", "", "", "", List.of()));

        assertThat(result.criteria()).hasSize(1);
    }
}
