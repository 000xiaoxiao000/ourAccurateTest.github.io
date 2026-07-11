package com.oAT.web.verification;

import com.oAT.ai.service.LLMService;
import com.oAT.web.verification.model.VerificationModels.AcceptanceCriterion;
import com.oAT.web.verification.model.VerificationModels.Finding;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VerificationAiAnalyzerTest {
    @Test
    void rejectsUnknownFindingTypesAndKeepsSchemaValidItems() {
        LLMService llm = mock(LLMService.class);
        when(llm.isAvailable()).thenReturn(true);
        when(llm.chat(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn("""
                {"findings":[
                  {"type":"INVENTED_BUG","perspective":"TEST","severity":"HIGH","description":"虚构"},
                  {"type":"MISSING_BOUNDARY_CASE","perspective":"TEST","severity":"MEDIUM",
                   "title":"缺少边界","description":"没有验证第5次错误","suggestion":"补充第4和第5次", 
                   "confidence":0.8,"verdict":"PARTIAL"}
                ]}
                """);
        VerificationAiAnalyzer analyzer = new VerificationAiAnalyzer(llm);
        AcceptanceCriterion ac = new AcceptanceCriterion("ac-1", "base-1", "REQ-1", "AC-1", "锁定",
                "密码错误5次后锁定", "line:2", "HIGH", true, false, 0.9);

        List<Finding> findings = analyzer.analyze("base-1", ac, List.of(), List.of(), "");

        assertThat(findings).singleElement().satisfies(item -> {
            assertThat(item.findingType()).isEqualTo("MISSING_BOUNDARY_CASE");
            assertThat(item.evidence()).isNotEmpty();
        });
    }

    @Test
    void fallsBackCleanlyWhenLlmIsUnavailable() {
        LLMService llm = mock(LLMService.class);
        when(llm.isAvailable()).thenReturn(false);
        VerificationAiAnalyzer analyzer = new VerificationAiAnalyzer(llm);
        AcceptanceCriterion ac = new AcceptanceCriterion("ac-1", "base-1", "REQ-1", "AC-1", "锁定",
                "密码错误5次后锁定", "line:2", "HIGH", true, false, 0.9);
        assertThat(analyzer.analyze("base-1", ac, List.of(), List.of(), "")).isEmpty();
    }
}
