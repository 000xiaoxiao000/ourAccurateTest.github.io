package com.oAT.web.verification;

import com.oAT.ai.service.LLMService;
import com.oAT.web.esDao.entity.StaticSourceClassInfo;
import com.oAT.web.esDao.entity.StaticSourceInfo;
import com.oAT.web.esDao.entity.StaticSourceMethodInfo;
import com.oAT.web.verification.VerificationAiOrchestrator.AiVerificationInput;
import com.oAT.web.verification.VerificationAiOrchestrator.AiVerificationResult;
import com.oAT.web.verification.model.VerificationModels.EvidenceLevel;
import com.oAT.web.verification.model.VerificationModels.Perspective;
import com.oAT.web.verification.model.VerificationModels.Severity;
import com.oAT.web.verification.model.VerificationModels.Verdict;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
        assertThat(result.findings()).hasSize(3);
        assertThat(result.findings().get(0).severity()).isEqualTo(Severity.HIGH);
        assertThat(result.findings().get(0).perspective()).isEqualTo(Perspective.DEVELOPMENT);
        assertThat(result.findings().get(0).verdict()).isEqualTo(Verdict.NOT_VERIFIABLE);
        assertThat(result.findings().get(0).acId()).isEqualTo(result.criteria().get(0).id());
        assertThat(result.findings()).extracting("perspective")
                .contains(Perspective.PRODUCT, Perspective.TEST, Perspective.DEVELOPMENT);
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
        assertThat(result.findings()).hasSize(3);
        assertThat(result.findings()).extracting("perspective")
                .containsExactlyInAnyOrder(Perspective.PRODUCT, Perspective.TEST, Perspective.DEVELOPMENT);
        assertThat(result.findings()).extracting("findingType")
                .contains("REQUIREMENT_CONFIRMATION", "MISSING_RUNTIME_EVIDENCE", "MISSING_IMPLEMENTATION");
        assertThat(result.findings()).allMatch(finding -> finding.verdict() == Verdict.PARTIAL);
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
        assertThat(result.findings()).hasSize(3);
        assertThat(result.findings()).extracting("perspective")
                .containsExactlyInAnyOrder(Perspective.PRODUCT, Perspective.TEST, Perspective.DEVELOPMENT);
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
        assertThat(result.findings()).hasSize(3);
        assertThat(result.findings()).extracting("perspective")
                .containsExactlyInAnyOrder(Perspective.PRODUCT, Perspective.TEST, Perspective.DEVELOPMENT);
        org.mockito.Mockito.verify(llmService, times(2)).chat(anyString(), anyString());
    }

    @Test
    void recoversCompletedItemsWhenProviderCutsOffClosingJsonMarkers() {
        LLMService llmService = mock(LLMService.class);
        when(llmService.isAvailable()).thenReturn(true);
        when(llmService.chat(anyString(), anyString())).thenReturn(
                "{\"criteria\":[{\"acKey\":\"AC-1\",\"content\":\"用户可以登录\"}]" );

        AiVerificationResult result = new VerificationAiOrchestrator(llmService).analyze(
                new AiVerificationInput("base-1", "用户可以登录", "", "", "", "", "", List.of()));

        assertThat(result.criteria()).hasSize(1);
        assertThat(result.findings()).hasSize(3);
        assertThat(result.findings()).extracting("perspective")
                .containsExactlyInAnyOrder(Perspective.PRODUCT, Perspective.TEST, Perspective.DEVELOPMENT);
        org.mockito.Mockito.verify(llmService, times(1)).chat(anyString(), anyString());
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

    @Test
    void fillsMissingCriteriaAndTestcasesFromMarkdownTables() {
        LLMService llmService = mock(LLMService.class);
        when(llmService.isAvailable()).thenReturn(true);
        when(llmService.chat(anyString(), anyString())).thenReturn("{\"criteria\":[]}");

        String requirements = """
                | 编号 | 功能 | 接口 | 需求描述 |
                | --- | --- | --- | --- |
                | U-001 | 获取用户列表 | POST /user/UserList | 根据 num1 返回用户列表 |
                | W3-001 | 数字参数分支 | GET /web3/testWeb3 | 执行比较和分支逻辑 |
                """;
        String testcases = """
                | 用例编号 | 接口 | 前置条件 | 输入 | 预期结果 | 优先级 |
                | --- | --- | --- | --- | --- | --- |
                | TC-U-001 | 用户列表正常 | 服务已启动 | body: 2 | HTTP 200，返回数组 | P0 |
                | TC-W3-001 | 分支验证 | 服务已启动 | num1=2 | HTTP 200 | P1 |
                """;

        AiVerificationResult result = new VerificationAiOrchestrator(llmService).analyze(
                new AiVerificationInput("base-1", requirements, testcases, "", "", "", "", List.of()));

        assertThat(result.criteria()).hasSize(2);
        assertThat(result.testcases()).hasSize(2);
        assertThat(result.traceLinks()).hasSize(2);
    }

    @Test
    void extractsNumberedAcceptanceCriteriaSectionInAdditionToRequirementTables() {
        LLMService llmService = mock(LLMService.class);
        when(llmService.isAvailable()).thenReturn(true);
        when(llmService.chat(anyString(), anyString())).thenReturn("{\"criteria\":[]}");

        String requirements = """
                | 编号 | 功能 | 接口 | 需求描述 |
                | --- | --- | --- | --- |
                | U-001 | 获取用户列表 | POST /user/UserList | 根据 num1 返回用户列表 |

                ## 8. 验收标准

                1. Maven 后端模块可编译，核心接口在本地 18083 端口可访问。
                2. 用户、明细、Web3 演示接口按接口定义返回预期状态码和响应体。
                """;

        AiVerificationResult result = new VerificationAiOrchestrator(llmService).analyze(
                new AiVerificationInput("base-1", requirements, "", "", "", "", "", List.of()));

        assertThat(result.criteria()).hasSize(3);
        assertThat(result.criteria()).extracting("requirementKey").contains("U-001", "G-001", "G-002");
    }

    @Test
    void fallsBackToDocumentExtractionWhenAiAndRepairJsonAreTruncated() {
        LLMService llmService = mock(LLMService.class);
        when(llmService.isAvailable()).thenReturn(true);
        when(llmService.chat(anyString(), anyString())).thenReturn(
                "{\"criteria\":[",
                "{\"criteria\":[{\"acKey\":\"AC-U-001\",\"content\":\"用户列表需要返回数组\"}");

        String requirements = """
                | 编号 | 功能 | 接口 | 需求描述 |
                | --- | --- | --- | --- |
                | U-001 | 获取用户列表 | POST /user/UserList | 根据 num1 返回用户列表 |
                """;
        String testcases = """
                | 用例编号 | 接口 | 前置条件 | 输入 | 预期结果 | 优先级 |
                | --- | --- | --- | --- | --- | --- |
                | TC-U-001 | 用户列表正常 | 服务已启动 | body: 2 | HTTP 200，返回数组 | P0 |
                """;

        AiVerificationResult result = new VerificationAiOrchestrator(llmService).analyze(
                new AiVerificationInput("base-1", requirements, testcases, "", "", "", "", List.of()));

        assertThat(result.criteria()).hasSize(1);
        assertThat(result.criteria().get(0).requirementKey()).isEqualTo("U-001");
        assertThat(result.testcases()).hasSize(1);
        assertThat(result.traceLinks()).hasSize(1);
        assertThat(result.findings()).hasSize(3);
        assertThat(result.findings()).extracting("perspective")
                .containsExactlyInAnyOrder(Perspective.PRODUCT, Perspective.TEST, Perspective.DEVELOPMENT);
        org.mockito.Mockito.verify(llmService, times(1)).chat(anyString(), anyString());
    }

    @Test
    void addsPendingSourceLinkFromStaticSourceIndexWhenAiOmitsIt() {
        LLMService llmService = mock(LLMService.class);
        when(llmService.isAvailable()).thenReturn(true);
        when(llmService.chat(anyString(), anyString())).thenReturn(
                "{\"criteria\":[{\"acKey\":\"AC-1\",\"content\":\"GET /web3/testWeb3 执行分支验证\"}]}" );
        StaticSourceClassInfo classInfo = new StaticSourceClassInfo();
        classInfo.setClassName("Web3Controller");
        StaticSourceMethodInfo methodInfo = new StaticSourceMethodInfo();
        methodInfo.setMethodName("testWeb3");
        classInfo.setMethodMaps(Map.of("testWeb3", methodInfo));

        AiVerificationResult result = new VerificationAiOrchestrator(llmService).analyze(
                new AiVerificationInput("base-1", "", "", "", "", "", "", List.of(new StaticSourceInfo(classInfo))));

        assertThat(result.traceLinks()).anyMatch(link -> "SOURCE_SYMBOL".equals(link.targetType())
                && "Web3Controller#testWeb3".equals(link.targetId())
                && link.reviewStatus() == com.oAT.web.verification.model.VerificationModels.ReviewStatus.PENDING);
    }

    @Test
    void removesMissingImplementationFindingWhenStaticSourceIndexProvesImplementation() {
        LLMService llmService = mock(LLMService.class);
        when(llmService.isAvailable()).thenReturn(true);
        when(llmService.chat(anyString(), anyString())).thenReturn("""
                {
                  "criteria": [{
                    "requirementKey": "D-005",
                    "acKey": "AC-D-005",
                    "title": "循环站点转换",
                    "content": "GET/detail/updated-site-info-loop 根据 loopCount 和 includeNullStation 生成站点列表并转换"
                  }],
                  "testcases": [{
                    "externalKey": "TC-D-005",
                    "title": "循环站点转换",
                    "steps": "调用 GET/detail/updated-site-info-loop",
                    "expected": "根据 loopCount 和 includeNullStation 生成站点列表"
                  }],
                  "findings": [{
                    "acKey": "AC-D-005",
                    "findingType": "MISSING_IMPLEMENTATION",
                    "perspective": "DEVELOPMENT",
                    "severity": "MEDIUM",
                    "title": "缺少源码实现",
                    "description": "没有对应静态源码类、方法或接口实现证据",
                    "verdict": "NOT_VERIFIABLE"
                  }]
                }
                """);
        StaticSourceClassInfo classInfo = new StaticSourceClassInfo();
        classInfo.setClassName("DetailController");
        classInfo.setSourceCode("""
                @RestController
                @RequestMapping("/detail")
                class DetailController {
                  @GetMapping("/updated-site-info-loop")
                  public List<SiteInfo> updatedSiteInfoLoop(Integer loopCount, Boolean includeNullStation) {
                    return buildSites(loopCount, includeNullStation);
                  }
                }
                """);
        StaticSourceMethodInfo methodInfo = new StaticSourceMethodInfo();
        methodInfo.setMethodName("updatedSiteInfoLoop");
        classInfo.setMethodMaps(Map.of("updatedSiteInfoLoop", methodInfo));

        AiVerificationResult result = new VerificationAiOrchestrator(llmService).analyze(
                new AiVerificationInput("base-1", "D-005 GET/detail/updated-site-info-loop 根据 loopCount 和 includeNullStation 生成站点列表并转换",
                        "TC-D-005 调用 GET/detail/updated-site-info-loop 后检查站点列表", "", "", "", "",
                        List.of(new StaticSourceInfo(classInfo))));

        assertThat(result.traceLinks()).anyMatch(link -> "SOURCE_SYMBOL".equals(link.targetType())
                && "DetailController#updatedSiteInfoLoop".equals(link.targetId()));
        assertThat(result.findings()).noneMatch(finding -> "MISSING_IMPLEMENTATION".equals(finding.findingType()));
    }

    @Test
    void enrichesTraceabilityAcrossRequirementTestcaseSourceRuntimeAndDefectAssets() {
        LLMService llmService = mock(LLMService.class);
        when(llmService.isAvailable()).thenReturn(true);
        when(llmService.chat(anyString(), anyString())).thenReturn("{\"criteria\":[],\"testcases\":[]}");

        String requirements = """
                | 编号 | 功能 | 接口 | 需求描述 |
                | --- | --- | --- | --- |
                | W3-001 | 数字参数分支验证 | GET /web3/testWeb3 | 接收 num1、num2，执行比较、差值、switch、集合遍历等逻辑 |
                """;
        String testcases = """
                | 用例编号 | 接口 | 输入 | 预期结果 | 优先级 |
                | --- | --- | --- | --- | --- |
                | TC-W3-001 | GET /web3/testWeb3 | num1=2&num2=1 | HTTP 200 | P1 |
                """;
        String source = """
                @RestController
                @RequestMapping("/web3")
                class Web3Controller {
                  @GetMapping("/testWeb3")
                  public Map<String,Object> testWeb3(Integer num1, Integer num2) { return new HashMap<>(); }
                }
                """;
        String execution = "执行报告: TC-W3-001 GET /web3/testWeb3 PASS";
        String coverage = "coverage: /web3/testWeb3 Web3Controller.testWeb3 lines covered";
        String defect = "RISK-006 GET /web3/testWeb3 现有 WebMvc 测试与代码路径不一致";

        AiVerificationResult result = new VerificationAiOrchestrator(llmService).analyze(
                new AiVerificationInput("base-1", requirements, testcases, defect, source, execution, coverage, List.of()));

        assertThat(result.criteria()).hasSize(1);
        assertThat(result.testcases()).hasSize(1);
        assertThat(result.traceLinks()).extracting("targetType")
                .contains("TESTCASE", "SOURCE_SYMBOL", "EXECUTION", "COVERAGE", "DEFECT");
        assertThat(result.findings()).anyMatch(finding -> "SATISFIED_SUMMARY".equals(finding.findingType())
                && finding.evidenceLevel() == EvidenceLevel.E4
                && finding.verdict() == Verdict.SATISFIED);
        assertThat(result.findings()).noneMatch(finding -> "MISSING_TESTCASE".equals(finding.findingType()));
        assertThat(result.findings()).noneMatch(finding -> "MISSING_RUNTIME_EVIDENCE".equals(finding.findingType()));
    }
}
