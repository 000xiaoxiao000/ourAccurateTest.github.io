package com.oAT.web.verification.qualitygate;

import com.oAT.web.verification.VerificationRepository;
import com.oAT.web.verification.model.VerificationModels.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Evaluates a QualityGatePolicy against a baseline and records the result.
 * Gate rules are deterministic — no LLM involvement.
 */
@Service
public class QualityGateService {

    private final VerificationRepository verificationRepository;
    private final JdbcTemplate jdbc;

    public QualityGateService(VerificationRepository verificationRepository, JdbcTemplate jdbc) {
        this.verificationRepository = verificationRepository;
        this.jdbc = jdbc;
    }

    // ── Policy CRUD ───────────────────────────────────────────────────────────

    public QualityGatePolicy createPolicy(String projectId, String userId, QualityGatePolicy policy) {
        QualityGatePolicy saved = new QualityGatePolicy(
                UUID.randomUUID().toString(), projectId,
                StringUtils.hasText(policy.name()) ? policy.name() : "默认质量门禁策略",
                clamp(policy.minTestcaseCoverageRate(), 0, 1),
                clamp(policy.minImplementationCoverageRate(), 0, 1),
                Math.max(0, policy.maxCriticalFindings()),
                Math.max(0, policy.maxHighFindings()),
                policy.requireAllAmbiguitiesResolved(),
                policy.requireChangeImpactVerified(),
                policy.blockOnStaleBaseline(),
                userId, LocalDateTime.now(), LocalDateTime.now());
        jdbc.update("""
                INSERT INTO oat_quality_gate_policy
                (id, project_id, name,
                 min_testcase_coverage_rate, min_implementation_coverage_rate,
                 max_critical_findings, max_high_findings,
                 require_all_ambiguities_resolved, require_change_impact_verified,
                 block_on_stale_baseline, created_by, create_time, update_time)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                saved.id(), saved.projectId(), saved.name(),
                saved.minTestcaseCoverageRate(), saved.minImplementationCoverageRate(),
                saved.maxCriticalFindings(), saved.maxHighFindings(),
                saved.requireAllAmbiguitiesResolved(), saved.requireChangeImpactVerified(),
                saved.blockOnStaleBaseline(), saved.createdBy(),
                ts(saved.createTime()), ts(saved.updateTime()));
        return saved;
    }

    public List<QualityGatePolicy> listPolicies(String projectId) {
        return jdbc.query(
                "SELECT * FROM oat_quality_gate_policy WHERE project_id = ? ORDER BY create_time DESC",
                this::policy, projectId);
    }

    public Optional<QualityGatePolicy> findPolicy(String projectId, String policyId) {
        return jdbc.query(
                "SELECT * FROM oat_quality_gate_policy WHERE project_id = ? AND id = ?",
                this::policy, projectId, policyId).stream().findFirst();
    }

    // ── Gate Evaluation ───────────────────────────────────────────────────────

    public QualityGateResult evaluate(String projectId, String baselineId,
                                      String policyId, String userId) {
        QualityGatePolicy pol = findPolicy(projectId, policyId)
                .orElseThrow(() -> new IllegalArgumentException("找不到质量门禁策略: " + policyId));
        Baseline baseline = verificationRepository.findBaseline(projectId, baselineId)
                .orElseThrow(() -> new IllegalArgumentException("找不到分析基线: " + baselineId));

        List<AcceptanceCriterion> criteria = verificationRepository.findCriteria(baselineId);
        List<TraceLink> links = verificationRepository.findTraceLinks(baselineId);
        List<Finding> findings = verificationRepository.findFindings(baselineId);
        Metrics metrics = buildMetrics(criteria, links, findings);

        List<GateExemption> activeExemptions = loadActiveExemptions(projectId, baselineId);
        Set<String> exemptedRules = new HashSet<>();
        for (GateExemption ex : activeExemptions) exemptedRules.add(ex.ruleId());

        List<GateFailure> failures = new ArrayList<>();

        if (pol.blockOnStaleBaseline() && baseline.status() == BaselineStatus.STALE
                && !exemptedRules.contains("STALE_BASELINE")) {
            failures.add(new GateFailure("STALE_BASELINE", "分析基线已过期", "STALE", "NOT_STALE"));
        }
        if (metrics.testcaseCoverageRate() < pol.minTestcaseCoverageRate()
                && !exemptedRules.contains("MIN_TESTCASE_COVERAGE")) {
            failures.add(new GateFailure("MIN_TESTCASE_COVERAGE", "用例覆盖率低于阈值",
                    pct(metrics.testcaseCoverageRate()), pct(pol.minTestcaseCoverageRate())));
        }
        if (metrics.implementationCoverageRate() < pol.minImplementationCoverageRate()
                && !exemptedRules.contains("MIN_IMPLEMENTATION_COVERAGE")) {
            failures.add(new GateFailure("MIN_IMPLEMENTATION_COVERAGE", "实现覆盖率低于阈值",
                    pct(metrics.implementationCoverageRate()), pct(pol.minImplementationCoverageRate())));
        }
        long criticalCount = findings.stream()
                .filter(f -> f.severity() == Severity.CRITICAL
                        && f.reviewStatus() != ReviewStatus.REJECTED
                        && f.reviewStatus() != ReviewStatus.EXEMPTED).count();
        if (criticalCount > pol.maxCriticalFindings() && !exemptedRules.contains("MAX_CRITICAL_FINDINGS")) {
            failures.add(new GateFailure("MAX_CRITICAL_FINDINGS", "严重问题数超过阈值",
                    String.valueOf(criticalCount), String.valueOf(pol.maxCriticalFindings())));
        }
        long highCount = findings.stream()
                .filter(f -> f.severity() == Severity.HIGH
                        && f.reviewStatus() != ReviewStatus.REJECTED
                        && f.reviewStatus() != ReviewStatus.EXEMPTED).count();
        if (highCount > pol.maxHighFindings() && !exemptedRules.contains("MAX_HIGH_FINDINGS")) {
            failures.add(new GateFailure("MAX_HIGH_FINDINGS", "高风险问题数超过阈值",
                    String.valueOf(highCount), String.valueOf(pol.maxHighFindings())));
        }
        if (pol.requireAllAmbiguitiesResolved() && !exemptedRules.contains("AMBIGUITIES_RESOLVED")) {
            long ambiguous = findings.stream()
                    .filter(f -> f.verdict() == Verdict.AMBIGUOUS
                            && f.reviewStatus() == ReviewStatus.PENDING).count();
            if (ambiguous > 0) {
                failures.add(new GateFailure("AMBIGUITIES_RESOLVED", "存在未处理的需求歧义",
                        ambiguous + " 条待处理", "0 条"));
            }
        }

        GateVerdict verdict = failures.isEmpty() ? GateVerdict.PASSED : GateVerdict.FAILED;
        if (!activeExemptions.isEmpty() && !failures.isEmpty()) verdict = GateVerdict.EXEMPTED;

        QualityGateResult result = new QualityGateResult(
                UUID.randomUUID().toString(), projectId, baselineId, policyId,
                verdict, failures, activeExemptions, metrics, userId, LocalDateTime.now());
        saveResult(result);
        return result;
    }

    public List<QualityGateResult> listResults(String projectId, String baselineId) {
        return jdbc.query("""
                SELECT * FROM oat_quality_gate_result
                WHERE project_id = ? AND baseline_id = ?
                ORDER BY evaluated_at DESC
                """, this::gateResult, projectId, baselineId);
    }

    // ── Exemptions ────────────────────────────────────────────────────────────

    public GateExemption createExemption(String projectId, String baselineId,
                                          String ruleId, String reason, String userId,
                                          LocalDateTime expiresAt) {
        Assert.hasText(ruleId, "ruleId 不能为空");
        Assert.hasText(reason, "豁免原因不能为空");
        GateExemption ex = new GateExemption(UUID.randomUUID().toString(), projectId, baselineId,
                ruleId, reason, userId, expiresAt, LocalDateTime.now());
        jdbc.update("""
                INSERT INTO oat_quality_gate_exemption
                (id, project_id, baseline_id, rule_id, reason, granted_by, expires_at, create_time)
                VALUES (?,?,?,?,?,?,?,?)
                """,
                ex.id(), ex.projectId(), ex.baselineId(), ex.ruleId(),
                ex.reason(), ex.grantedBy(), ts(ex.expiresAt()), ts(ex.createTime()));
        return ex;
    }

    public List<GateExemption> listExemptions(String projectId, String baselineId) {
        return jdbc.query("""
                SELECT * FROM oat_quality_gate_exemption
                WHERE project_id = ? AND baseline_id = ?
                ORDER BY create_time DESC
                """, this::exemption, projectId, baselineId);
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private List<GateExemption> loadActiveExemptions(String projectId, String baselineId) {
        return jdbc.query("""
                SELECT * FROM oat_quality_gate_exemption
                WHERE project_id = ? AND baseline_id = ?
                  AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
                """, this::exemption, projectId, baselineId);
    }

    private Metrics buildMetrics(List<AcceptanceCriterion> criteria,
                                  List<TraceLink> links, List<Finding> findings) {
        Set<String> tested = new HashSet<>(), implemented = new HashSet<>(),
                executed = new HashSet<>(), runtimeCovered = new HashSet<>();
        for (TraceLink l : links) {
            switch (l.targetType()) {
                case "TESTCASE" -> tested.add(l.sourceId());
                case "SOURCE_SYMBOL" -> implemented.add(l.sourceId());
                case "EXECUTION" -> executed.add(l.sourceId());
                case "COVERAGE" -> runtimeCovered.add(l.sourceId());
            }
        }
        Set<String> closed = new HashSet<>(tested); closed.retainAll(implemented);
        int total = criteria.size();
        long open = findings.stream().filter(f -> f.reviewStatus() == ReviewStatus.PENDING
                || f.reviewStatus() == ReviewStatus.CONFIRMED).count();
        return new Metrics(total, tested.size(), implemented.size(), executed.size(),
                runtimeCovered.size(), closed.size(), (int) open,
                rate(tested.size(), total), rate(implemented.size(), total),
                rate(executed.size(), total), rate(runtimeCovered.size(), total),
                rate(closed.size(), total), 0, 0);
    }

    private void saveResult(QualityGateResult r) {
        jdbc.update("""
                INSERT INTO oat_quality_gate_result
                (id, project_id, baseline_id, policy_id, verdict, failures_count,
                 evaluated_by, evaluated_at)
                VALUES (?,?,?,?,?,?,?,?)
                """,
                r.id(), r.projectId(), r.baselineId(), r.policyId(),
                r.verdict().name(), r.failures().size(), r.evaluatedBy(), ts(r.evaluatedAt()));
    }

    private QualityGatePolicy policy(ResultSet rs, int row) throws SQLException {
        return new QualityGatePolicy(rs.getString("id"), rs.getString("project_id"),
                rs.getString("name"),
                rs.getDouble("min_testcase_coverage_rate"),
                rs.getDouble("min_implementation_coverage_rate"),
                rs.getInt("max_critical_findings"), rs.getInt("max_high_findings"),
                rs.getBoolean("require_all_ambiguities_resolved"),
                rs.getBoolean("require_change_impact_verified"),
                rs.getBoolean("block_on_stale_baseline"),
                rs.getString("created_by"),
                time(rs.getTimestamp("create_time")), time(rs.getTimestamp("update_time")));
    }

    private QualityGateResult gateResult(ResultSet rs, int row) throws SQLException {
        return new QualityGateResult(rs.getString("id"), rs.getString("project_id"),
                rs.getString("baseline_id"), rs.getString("policy_id"),
                GateVerdict.valueOf(rs.getString("verdict")),
                List.of(), List.of(), null,
                rs.getString("evaluated_by"), time(rs.getTimestamp("evaluated_at")));
    }

    private GateExemption exemption(ResultSet rs, int row) throws SQLException {
        return new GateExemption(rs.getString("id"), rs.getString("project_id"),
                rs.getString("baseline_id"), rs.getString("rule_id"),
                rs.getString("reason"), rs.getString("granted_by"),
                time(rs.getTimestamp("expires_at")), time(rs.getTimestamp("create_time")));
    }

    private String pct(double v) { return Math.round(v * 1000) / 10.0 + "%"; }
    private double rate(int v, int t) { return t == 0 ? 0 : Math.round((double) v / t * 10000) / 10000.0; }
    private double clamp(double v, double min, double max) { return Math.max(min, Math.min(max, v)); }
    private Timestamp ts(LocalDateTime v) { return v == null ? null : Timestamp.valueOf(v); }
    private LocalDateTime time(Timestamp v) { return v == null ? null : v.toLocalDateTime(); }
}
