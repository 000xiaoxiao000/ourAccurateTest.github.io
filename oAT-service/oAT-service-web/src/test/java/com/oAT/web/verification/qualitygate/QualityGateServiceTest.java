package com.oAT.web.verification.qualitygate;

import com.oAT.web.esDao.ClassCoverageIndexRepository;
import com.oAT.web.verification.VerificationRepository;
import com.oAT.web.verification.VerificationRepository.StaleRuntimeExecution;
import com.oAT.web.verification.model.VerificationModels.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QualityGateServiceTest {

    private VerificationRepository verificationRepository;
    private JdbcTemplate jdbc;
    private ClassCoverageIndexRepository coverageRepository;
    private QualityGateService service;

    private static final String PROJECT  = "proj-1";
    private static final String BASELINE = "bl-1";
    private static final String POLICY   = "pol-1";
    private static final String USER     = "user-1";

    @BeforeEach
    void setUp() {
        verificationRepository = mock(VerificationRepository.class);
        jdbc = mock(JdbcTemplate.class);
        coverageRepository = mock(ClassCoverageIndexRepository.class);
        service = new QualityGateService(verificationRepository, jdbc, coverageRepository);

        when(verificationRepository.findBaseline(PROJECT, BASELINE))
                .thenReturn(Optional.of(baseline(BASELINE, "commit-abc", BaselineStatus.COMPLETED)));
        when(verificationRepository.findCriteria(BASELINE)).thenReturn(List.of(ac("ac-1")));
        when(verificationRepository.findTraceLinks(BASELINE)).thenReturn(List.of());
        when(verificationRepository.findFindings(BASELINE)).thenReturn(List.of());
        when(verificationRepository.findStaleRuntimeExecutions(BASELINE)).thenReturn(List.of());
        when(coverageRepository.findByReportId(any())).thenReturn(List.of());
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class))).thenReturn(List.of());
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
    }

    // ── enforcement mode ──────────────────────────────────────────────────────

    @Test
    void shadow_mode_never_blocks_even_on_failures() {
        stubPolicy(policy(POLICY, 1.0, 1.0, 0, 0, false, false, false));
        stubSaveResult();

        var decision = service.evaluateWithMode(PROJECT, BASELINE, POLICY, USER,
                QualityGateService.EnforcementMode.SHADOW);

        assertEquals("SHADOW", decision.mode());
        assertFalse(decision.blocked(), "SHADOW mode must never block");
        assertEquals(GateVerdict.WARNING, decision.effectiveVerdict());
        verify(jdbc).update(contains("enforcement_mode"), eq("SHADOW"), eq(false), anyString(), anyString());
    }

    @Test
    void soft_mode_warns_but_does_not_block() {
        stubPolicy(policy(POLICY, 1.0, 1.0, 0, 0, false, false, false));
        stubSaveResult();

        var decision = service.evaluateWithMode(PROJECT, BASELINE, POLICY, USER,
                QualityGateService.EnforcementMode.SOFT);

        assertEquals("SOFT", decision.mode());
        assertFalse(decision.blocked());
        assertEquals(GateVerdict.WARNING, decision.effectiveVerdict());
    }

    @Test
    void hard_mode_blocks_when_failures_present() {
        stubPolicy(policy(POLICY, 1.0, 1.0, 0, 0, false, false, false));
        stubSaveResult();

        var decision = service.evaluateWithMode(PROJECT, BASELINE, POLICY, USER,
                QualityGateService.EnforcementMode.HARD);

        assertEquals("HARD", decision.mode());
        assertTrue(decision.blocked(), "HARD mode must block when gate fails");
    }

    @Test
    void hard_mode_passes_when_all_criteria_met() {
        // All AC have testcase + implementation links → coverage rates = 1.0
        String acId = "ac-1";
        when(verificationRepository.findTraceLinks(BASELINE)).thenReturn(List.of(
                link(acId, "TESTCASE",      "tc-1"),
                link(acId, "SOURCE_SYMBOL", "sym-1")));
        stubPolicy(policy(POLICY, 0.0, 0.0, 10, 10, false, false, false));
        stubSaveResult();

        var decision = service.evaluateWithMode(PROJECT, BASELINE, POLICY, USER,
                QualityGateService.EnforcementMode.HARD);

        assertFalse(decision.blocked());
        assertNotEquals(GateVerdict.FAILED, decision.effectiveVerdict());
    }

    // ── requireChangeImpactVerified ───────────────────────────────────────────

    @Test
    void stale_runtime_execution_fails_CHANGE_IMPACT_VERIFIED_rule() {
        when(verificationRepository.findStaleRuntimeExecutions(BASELINE)).thenReturn(List.of(
                new StaleRuntimeExecution(BASELINE, PROJECT, "commit-abc", "wrong-commit",
                        "exec-1", OffsetDateTime.now())));
        stubPolicy(policy(POLICY, 0.0, 0.0, 10, 10, false, false, true));
        stubSaveResult();

        var result = service.evaluate(PROJECT, BASELINE, POLICY, USER);

        assertTrue(result.failures().stream()
                .anyMatch(f -> "CHANGE_IMPACT_VERIFIED".equals(f.ruleId())),
                "commit mismatch must trigger CHANGE_IMPACT_VERIFIED failure");
    }

    @Test
    void no_stale_executions_passes_CHANGE_IMPACT_VERIFIED() {
        when(verificationRepository.findStaleRuntimeExecutions(BASELINE)).thenReturn(List.of());
        stubPolicy(policy(POLICY, 0.0, 0.0, 10, 10, false, false, true));
        stubSaveResult();

        var result = service.evaluate(PROJECT, BASELINE, POLICY, USER);

        assertTrue(result.failures().stream()
                .noneMatch(f -> "CHANGE_IMPACT_VERIFIED".equals(f.ruleId())));
    }

    @Test
    void unreviewed_change_impact_finding_fails_CHANGE_IMPACT_VERIFIED() {
        when(verificationRepository.findFindings(BASELINE)).thenReturn(List.of(
                finding("ac-1", "CHANGE_IMPACT", ReviewStatus.PENDING)));
        stubPolicy(policy(POLICY, 0.0, 0.0, 10, 10, false, false, true));
        stubSaveResult();

        var result = service.evaluate(PROJECT, BASELINE, POLICY, USER);

        assertTrue(result.failures().stream()
                .anyMatch(f -> "CHANGE_IMPACT_VERIFIED".equals(f.ruleId())));
    }

    // ── stale baseline ────────────────────────────────────────────────────────

    @Test
    void stale_baseline_fails_when_blockOnStaleBaseline_is_true() {
        when(verificationRepository.findBaseline(PROJECT, BASELINE))
                .thenReturn(Optional.of(baseline(BASELINE, "commit-abc", BaselineStatus.STALE)));
        stubPolicy(policy(POLICY, 0.0, 0.0, 10, 10, true, false, false));
        stubSaveResult();

        var result = service.evaluate(PROJECT, BASELINE, POLICY, USER);

        assertTrue(result.failures().stream()
                .anyMatch(f -> "STALE_BASELINE".equals(f.ruleId())));
    }

    // ── exemption ─────────────────────────────────────────────────────────────

    @Test
    void exempted_rule_does_not_cause_failure() {
        // STALE_BASELINE is exempted — gate should pass without that failure
        when(verificationRepository.findBaseline(PROJECT, BASELINE))
                .thenReturn(Optional.of(baseline(BASELINE, "commit-abc", BaselineStatus.STALE)));
        stubPolicy(policy(POLICY, 0.0, 0.0, 10, 10, true, false, false));
        stubSaveResult();
        // Return an active exemption for STALE_BASELINE
        when(jdbc.query(contains("oat_quality_gate_exemption"),
                any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class))).thenReturn(List.of(
                new GateExemption(UUID.randomUUID().toString(), PROJECT, BASELINE,
                        "STALE_BASELINE", "计划性维护", USER, null, LocalDateTime.now())));

        var result = service.evaluate(PROJECT, BASELINE, POLICY, USER);

        // The exempted rule must not appear as a failure entry
        assertTrue(result.failures().stream()
                .noneMatch(f -> "STALE_BASELINE".equals(f.ruleId())),
                "Exempted rule must not appear as a failure");
        // With STALE_BASELINE exempted and no other failing rules, the gate passes (not EXEMPTED)
        // EXEMPTED verdict only applies when exemptions cover some — but not all — failures.
        // When exemptions remove ALL failures the result is PASSED; that is correct behaviour.
        assertNotEquals(GateVerdict.FAILED, result.verdict(),
                "Verdict must not be FAILED when the only failing rule is exempted");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void stubPolicy(QualityGatePolicy pol) {
        when(jdbc.query(contains("oat_quality_gate_policy"),
                any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class))).thenReturn(List.of(pol));
    }

    private void stubSaveResult() {
        when(jdbc.update(contains("oat_quality_gate_result"), any(Object[].class))).thenReturn(1);
        when(jdbc.update(contains("enforcement_mode"), any(), any(), any(), any())).thenReturn(1);
    }

    private QualityGatePolicy policy(String id,
                                      double minTC, double minImpl,
                                      int maxCrit, int maxHigh,
                                      boolean blockStale, boolean requireAmbig,
                                      boolean requireImpact) {
        return new QualityGatePolicy(id, PROJECT, "test-policy",
                minTC, minImpl, maxCrit, maxHigh,
                requireAmbig, requireImpact, blockStale,
                USER, LocalDateTime.now(), LocalDateTime.now());
    }

    private Baseline baseline(String id, String commit, BaselineStatus status) {
        return new Baseline(id, PROJECT, "name", null, null, null, null, null, null,
                "http://repo", "main", commit, "v1", status, Freshness.LIVE,
                USER, LocalDateTime.now(), LocalDateTime.now());
    }

    private AcceptanceCriterion ac(String id) {
        return new AcceptanceCriterion(id, BASELINE, "REQ-1", "AC-1",
                "title", "content", null, "MEDIUM", true, false, 0.9);
    }

    private TraceLink link(String acId, String targetType, String targetId) {
        return new TraceLink(UUID.randomUUID().toString(), BASELINE, "AC", acId,
                targetType, targetId, "VERIFIED_BY", "TEST", 0.9,
                EvidenceLevel.E2, ReviewStatus.PENDING, Map.of());
    }

    private Finding finding(String acId, String findingType, ReviewStatus status) {
        return new Finding(UUID.randomUUID().toString(), BASELINE, acId, findingType,
                Perspective.CROSS, Severity.HIGH, "title", "desc", null,
                0.8, EvidenceLevel.E2, Verdict.PARTIAL, status,
                List.of(), null, null, null);
    }
}
