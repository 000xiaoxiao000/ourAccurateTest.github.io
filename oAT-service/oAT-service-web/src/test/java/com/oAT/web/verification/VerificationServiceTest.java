package com.oAT.web.verification;

import com.oAT.web.esDao.StaticInfoRepository;
import com.oAT.web.verification.VerificationAiOrchestrator.AiVerificationInput;
import com.oAT.web.verification.VerificationAiOrchestrator.AiVerificationResult;
import com.oAT.web.verification.model.VerificationModels.*;
import com.oAT.web.verification.storage.AssetContentStore;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VerificationServiceTest {
    @Test
    void writeBackFindingRecordsAuditAndMarksFindingWrittenBack() {
        VerificationRepository repository = mock(VerificationRepository.class);
        VerificationAiWriteBackComposer writeBackComposer = mock(VerificationAiWriteBackComposer.class);
        VerificationService service = new VerificationService(repository, mock(StaticInfoRepository.class),
                mock(VerificationAiOrchestrator.class), writeBackComposer, mock(AssetContentStore.class), Runnable::run);
        Finding finding = new Finding("finding-1", "base-1", "ac-1", "MISSING_TESTCASE",
                Perspective.TEST, Severity.HIGH, "缺少用例", "没有覆盖", "补充用例",
                0.8, EvidenceLevel.E1, Verdict.NOT_SATISFIED, ReviewStatus.PENDING,
                List.of(Map.of("type", "AC")), null, null, null);

        when(repository.findFinding("project-1", "finding-1")).thenReturn(Optional.of(finding));
        when(repository.findCriteria("base-1")).thenReturn(List.of());
        when(repository.findTraceLinks("base-1")).thenReturn(List.of());
        when(repository.findTestcases("base-1")).thenReturn(List.of());
        when(writeBackComposer.compose(any())).thenReturn("AI生成的可处理回写内容");
        when(repository.reviewFinding(eq("project-1"), eq("finding-1"), eq(ReviewStatus.WRITTEN_BACK),
                eq("u1"), eq("AI生成的可处理回写内容"), eq("https://jira.example.com/browse/REQ-1"))).thenReturn(true);

        WriteBackAction action = service.writeBackFinding("project-1", "finding-1", "u1",
                new VerificationService.WriteBackFinding("ai-writeback", "https://jira.example.com/browse/REQ-1",
                        "请给测试处理", "TEST"));

        assertThat(action.baselineId()).isEqualTo("base-1");
        assertThat(action.status()).isEqualTo("AI_GENERATED");
        assertThat(action.message()).isEqualTo("AI生成的可处理回写内容");
        verify(repository).saveWriteBackAction(any(WriteBackAction.class));
    }

    @Test
    void analyzeLoadsAssetContentFromContentStore() {
        VerificationRepository repository = mock(VerificationRepository.class);
        VerificationAiOrchestrator orchestrator = mock(VerificationAiOrchestrator.class);
        AssetContentStore contentStore = mock(AssetContentStore.class);
        VerificationService service = new VerificationService(repository, mock(StaticInfoRepository.class),
                orchestrator, mock(VerificationAiWriteBackComposer.class), contentStore, Runnable::run);
        LocalDateTime now = LocalDateTime.now();
        Baseline baseline = new Baseline("base-1", "project-1", "AI验证", "req-1", "tc-1",
                null, null, null, null, null, null, null,
                "1.0.0", BaselineStatus.CREATED, Freshness.MANUAL, "u1", now, now);
        AssetSnapshot requirement = asset("req-1", "project-1", AssetType.REQUIREMENT, "req-key");
        AssetSnapshot testcase = asset("tc-1", "project-1", AssetType.TESTCASE, "tc-key");
        AcceptanceCriterion ac = new AcceptanceCriterion("ac-1", "base-1", "REQ-1", "AC-1",
                "登录", "用户可以登录", "需求:1", "HIGH", true, false, 0.9);

        when(repository.findBaseline("project-1", "base-1")).thenReturn(Optional.of(baseline));
        when(repository.findRunningAnalysisJob("project-1", "base-1")).thenReturn(Optional.empty());
        when(repository.findAsset("project-1", "req-1")).thenReturn(Optional.of(requirement));
        when(repository.findAsset("project-1", "tc-1")).thenReturn(Optional.of(testcase));
        when(repository.findAssets("project-1", AssetType.DEFECT)).thenReturn(List.of());
        when(contentStore.load("req-key")).thenReturn("REQ-1 用户可以登录");
        when(contentStore.load("tc-key")).thenReturn("TC-1 登录成功");
        when(orchestrator.analyze(any())).thenReturn(new AiVerificationResult(List.of(ac), List.of(), List.of(), List.of()));
        when(repository.findCriteria("base-1")).thenReturn(List.of(ac));
        when(repository.findTestcases("base-1")).thenReturn(List.of());
        when(repository.findTraceLinks("base-1")).thenReturn(List.of());
        when(repository.findFindings("base-1")).thenReturn(List.of());

        service.startAnalysis("project-1", "base-1", "u1");

        verify(orchestrator).analyze(argThat((AiVerificationInput input) ->
                "REQ-1 用户可以登录".equals(input.requirementContent())
                        && "TC-1 登录成功".equals(input.testcaseContent())));
    }

    private static AssetSnapshot asset(String id, String projectId, AssetType type, String storageKey) {
        return new AssetSnapshot(id, projectId, type, SourceType.FILE, null, null, null,
                id + ".txt", "hash", null, "MYSQL", storageKey, 128,
                "preview", Map.of(), Freshness.MANUAL, "u1", LocalDateTime.now());
    }
}
