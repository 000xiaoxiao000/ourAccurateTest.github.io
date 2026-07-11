package com.oAT.web.verification;

import com.oAT.web.esDao.StaticInfoRepository;
import com.oAT.web.verification.VerificationService.GatePolicy;
import com.oAT.web.verification.model.VerificationModels.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VerificationServiceTest {
    @Test
    void gateUsesStaticAndRuntimeEvidenceMetrics() {
        VerificationRepository repository = mock(VerificationRepository.class);
        VerificationService service = new VerificationService(repository, new VerificationProjectionParser(),
                mock(StaticInfoRepository.class), new VerificationAiAnalyzer(null));
        LocalDateTime now = LocalDateTime.now();
        Baseline baseline = new Baseline("base-1", "project-1", "发布验证", "req-1", "tc-1",
                "src-1", "exec-1", "cov-1", null, null, null, "abc",
                "1.0.0", BaselineStatus.WAITING_REVIEW, Freshness.MANUAL, "u1", now, now);
        AssetSnapshot requirement = asset("req-1", "project-1", AssetType.REQUIREMENT);
        AssetSnapshot testcase = asset("tc-1", "project-1", AssetType.TESTCASE);
        AcceptanceCriterion ac = new AcceptanceCriterion("ac-1", "base-1", "REQ-1", "AC-1",
                "锁定", "密码错误5次后锁定", "line:1", "HIGH", true, false, 0.9);

        when(repository.findBaseline("project-1", "base-1")).thenReturn(Optional.of(baseline));
        when(repository.findAsset("project-1", "req-1")).thenReturn(Optional.of(requirement));
        when(repository.findAsset("project-1", "tc-1")).thenReturn(Optional.of(testcase));
        when(repository.findCriteria("base-1")).thenReturn(List.of(ac));
        when(repository.findTestcases("base-1")).thenReturn(List.of());
        when(repository.findTraceLinks("base-1")).thenReturn(List.of(
                link("base-1", "ac-1", "TESTCASE", "tc-case", EvidenceLevel.E1),
                link("base-1", "ac-1", "SOURCE_SYMBOL", "LoginService", EvidenceLevel.E2),
                link("base-1", "ac-1", "EXECUTION", "run-1", EvidenceLevel.E3),
                link("base-1", "ac-1", "COVERAGE", "cov-1", EvidenceLevel.E4)
        ));
        when(repository.findFindings("base-1")).thenReturn(List.of());

        GateResult result = service.evaluateGate("project-1", "base-1", new GatePolicy(1, 1, 1, 1, 0, true));

        assertThat(result.status()).isEqualTo(GateStatus.PASSED);
        assertThat(result.metrics().executionEvidenceRate()).isEqualTo(1);
        assertThat(result.metrics().runtimeCoverageRate()).isEqualTo(1);
    }

    @Test
    void writeBackFindingRecordsAuditAndMarksFindingWrittenBack() {
        VerificationRepository repository = mock(VerificationRepository.class);
        VerificationService service = new VerificationService(repository, new VerificationProjectionParser(),
                mock(StaticInfoRepository.class), new VerificationAiAnalyzer(null));
        Finding finding = new Finding("finding-1", "base-1", "ac-1", "MISSING_TESTCASE",
                Perspective.TEST, Severity.HIGH, "缺少用例", "没有覆盖", "补充用例",
                0.8, EvidenceLevel.E1, Verdict.NOT_SATISFIED, ReviewStatus.PENDING,
                List.of(Map.of("type", "AC")), null, null, null);

        when(repository.findFinding("project-1", "finding-1")).thenReturn(Optional.of(finding));
        when(repository.reviewFinding(eq("project-1"), eq("finding-1"), eq(ReviewStatus.WRITTEN_BACK),
                eq("u1"), eq("已创建外部任务"), eq("https://jira.example.com/browse/REQ-1"))).thenReturn(true);

        WriteBackAction action = service.writeBackFinding("project-1", "finding-1", "u1",
                new VerificationService.WriteBackFinding("link-only", "https://jira.example.com/browse/REQ-1", "已创建外部任务"));

        assertThat(action.baselineId()).isEqualTo("base-1");
        assertThat(action.status()).isEqualTo("RECORDED");
        verify(repository).saveWriteBackAction(any(WriteBackAction.class));
    }

    private static AssetSnapshot asset(String id, String projectId, AssetType type) {
        return new AssetSnapshot(id, projectId, type, SourceType.FILE, null, null, null,
                id + ".txt", "hash", "content", Map.of(), Freshness.MANUAL, "u1", LocalDateTime.now());
    }

    private static TraceLink link(String baselineId, String acId, String targetType, String targetId, EvidenceLevel level) {
        return new TraceLink(targetType + "-1", baselineId, "AC", acId, targetType, targetId,
                "VERIFIED_BY", "TEST", 0.8, level, ReviewStatus.PENDING, Map.of());
    }
}
