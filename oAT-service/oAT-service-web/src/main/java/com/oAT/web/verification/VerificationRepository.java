package com.oAT.web.verification;

import com.oAT.web.common.UtilJson;
import com.oAT.web.verification.model.VerificationModels.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class VerificationRepository {
    private final JdbcTemplate jdbc;

    public VerificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void saveAsset(AssetSnapshot asset) {
        jdbc.update("""
                INSERT INTO oat_verification_asset
                (id, project_id, asset_type, source_type, external_id, external_url, source_version,
                 file_name, content_hash, content_text, metadata_json, freshness, imported_by, captured_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), ?, ?, ?)
                """, asset.id(), asset.projectId(), asset.assetType().name(), asset.sourceType().name(),
                asset.externalId(), asset.externalUrl(), asset.sourceVersion(), asset.fileName(), asset.contentHash(),
                asset.content(), json(asset.metadata()), asset.freshness().name(), asset.importedBy(), ts(asset.capturedAt()));
    }

    public Optional<AssetSnapshot> findAsset(String projectId, String id) {
        return jdbc.query("SELECT * FROM oat_verification_asset WHERE project_id = ? AND id = ?",
                this::asset, projectId, id).stream().findFirst();
    }

    public List<AssetSnapshot> findAssets(String projectId, AssetType type) {
        return jdbc.query("SELECT * FROM oat_verification_asset WHERE project_id = ? AND asset_type = ? ORDER BY create_time DESC",
                this::asset, projectId, type.name());
    }

    public void saveBaseline(Baseline baseline) {
        jdbc.update("""
                INSERT INTO oat_verification_baseline
                (id, project_id, name, requirement_asset_id, testcase_asset_id, source_asset_id,
                 execution_asset_id, coverage_asset_id, source_app_id,
                 repository_url, source_branch, source_commit, analyzer_version, status, freshness, created_by,
                 create_time, update_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, baseline.id(), baseline.projectId(), baseline.name(), baseline.requirementAssetId(),
                baseline.testcaseAssetId(), baseline.sourceAssetId(), baseline.executionAssetId(), baseline.coverageAssetId(),
                baseline.sourceAppId(), baseline.repositoryUrl(), baseline.sourceBranch(), baseline.sourceCommit(),
                baseline.analyzerVersion(), baseline.status().name(), baseline.freshness().name(), baseline.createdBy(),
                ts(baseline.createTime()), ts(baseline.updateTime()));
    }

    public void updateBaselineStatus(String id, BaselineStatus status) {
        jdbc.update("UPDATE oat_verification_baseline SET status = ?, update_time = CURRENT_TIMESTAMP WHERE id = ?",
                status.name(), id);
    }

    public void markBaselineStale(String id) {
        jdbc.update("""
                UPDATE oat_verification_baseline
                SET status = ?, freshness = ?, update_time = CURRENT_TIMESTAMP
                WHERE id = ?
                """, BaselineStatus.STALE.name(), Freshness.STALE.name(), id);
    }

    public Optional<Baseline> findBaseline(String projectId, String id) {
        return jdbc.query("SELECT * FROM oat_verification_baseline WHERE project_id = ? AND id = ?",
                this::baseline, projectId, id).stream().findFirst();
    }

    public List<Baseline> findBaselines(String projectId) {
        return jdbc.query("SELECT * FROM oat_verification_baseline WHERE project_id = ? ORDER BY create_time DESC",
                this::baseline, projectId);
    }

    @Transactional
    public void replaceAnalysis(String baselineId, List<AcceptanceCriterion> criteria,
                                List<TestcaseProjection> testcases, List<TraceLink> links, List<Finding> findings) {
        jdbc.update("DELETE FROM oat_verification_finding WHERE baseline_id = ?", baselineId);
        jdbc.update("DELETE FROM oat_verification_trace_link WHERE baseline_id = ?", baselineId);
        jdbc.update("DELETE FROM oat_verification_ac WHERE baseline_id = ?", baselineId);
        jdbc.update("DELETE FROM oat_verification_testcase WHERE baseline_id = ?", baselineId);
        criteria.forEach(this::saveCriterion);
        testcases.forEach(this::saveTestcase);
        links.forEach(this::saveTraceLink);
        findings.forEach(this::saveFinding);
    }

    public List<AcceptanceCriterion> findCriteria(String baselineId) {
        return jdbc.query("SELECT * FROM oat_verification_ac WHERE baseline_id = ? ORDER BY requirement_key, ac_key",
                this::criterion, baselineId);
    }

    public List<TestcaseProjection> findTestcases(String baselineId) {
        return jdbc.query("SELECT * FROM oat_verification_testcase WHERE baseline_id = ? ORDER BY external_key",
                this::testcase, baselineId);
    }

    public List<TraceLink> findTraceLinks(String baselineId) {
        return jdbc.query("SELECT * FROM oat_verification_trace_link WHERE baseline_id = ? ORDER BY confidence DESC",
                this::traceLink, baselineId);
    }

    public List<Finding> findFindings(String baselineId) {
        return jdbc.query("SELECT * FROM oat_verification_finding WHERE baseline_id = ? ORDER BY FIELD(severity, 'CRITICAL','HIGH','MEDIUM','LOW','INFO'), create_time",
                this::finding, baselineId);
    }

    public boolean reviewFinding(String projectId, String findingId, ReviewStatus status, String userId,
                                 String reason, String externalUrl) {
        int updated = jdbc.update("""
                UPDATE oat_verification_finding f
                JOIN oat_verification_baseline b ON b.id = f.baseline_id
                SET f.review_status = ?, f.reviewed_by = ?, f.review_reason = ?,
                    f.external_work_item_url = ?, f.update_time = CURRENT_TIMESTAMP
                WHERE b.project_id = ? AND f.id = ?
                """, status.name(), userId, reason, externalUrl, projectId, findingId);
        return updated == 1;
    }

    public boolean reviewTraceLink(String projectId, String traceLinkId, ReviewStatus status) {
        int updated = jdbc.update("""
                UPDATE oat_verification_trace_link t
                JOIN oat_verification_baseline b ON b.id = t.baseline_id
                SET t.review_status = ?, t.update_time = CURRENT_TIMESTAMP
                WHERE b.project_id = ? AND t.id = ?
                """, status.name(), projectId, traceLinkId);
        return updated == 1;
    }

    public void saveGateResult(GateResult result) {
        jdbc.update("""
                INSERT INTO oat_verification_gate_result
                (id, baseline_id, status, policy_json, metrics_json, reasons_json, create_time)
                VALUES (?, ?, ?, CAST(? AS JSON), CAST(? AS JSON), CAST(? AS JSON), ?)
                """, result.id(), result.baselineId(), result.status().name(), json(result.policy()),
                json(result.metrics()), json(result.reasons()), ts(result.createTime()));
    }

    public Optional<Finding> findFinding(String projectId, String findingId) {
        return jdbc.query("""
                SELECT f.* FROM oat_verification_finding f
                JOIN oat_verification_baseline b ON b.id = f.baseline_id
                WHERE b.project_id = ? AND f.id = ?
                """, this::finding, projectId, findingId).stream().findFirst();
    }

    public void saveWriteBackAction(WriteBackAction action) {
        jdbc.update("""
                INSERT INTO oat_verification_writeback_action
                (id, project_id, baseline_id, finding_id, connector_type, external_url,
                 status, message, created_by, create_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, action.id(), action.projectId(), action.baselineId(), action.findingId(),
                action.connectorType(), action.externalUrl(), action.status(), action.message(),
                action.createdBy(), ts(action.createTime()));
    }

    public List<WriteBackAction> findWriteBackActions(String baselineId) {
        return jdbc.query("""
                SELECT * FROM oat_verification_writeback_action
                WHERE baseline_id = ?
                ORDER BY create_time DESC
                """, this::writeBackAction, baselineId);
    }

    private void saveCriterion(AcceptanceCriterion item) {
        jdbc.update("""
                INSERT INTO oat_verification_ac
                (id, baseline_id, requirement_key, ac_key, title, content_text, source_locator, priority,
                 testable, ambiguity, confidence) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, item.id(), item.baselineId(), item.requirementKey(), item.acKey(), item.title(), item.content(),
                item.sourceLocator(), item.priority(), item.testable(), item.ambiguity(), item.confidence());
    }

    private void saveTestcase(TestcaseProjection item) {
        jdbc.update("""
                INSERT INTO oat_verification_testcase
                (id, baseline_id, external_key, title, preconditions_text, steps_text, test_data_text,
                 expected_text, requirement_refs, source_locator) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, item.id(), item.baselineId(), item.externalKey(), item.title(), item.preconditions(), item.steps(),
                item.testData(), item.expected(), item.requirementRefs(), item.sourceLocator());
    }

    private void saveTraceLink(TraceLink item) {
        jdbc.update("""
                INSERT INTO oat_verification_trace_link
                (id, baseline_id, source_type, source_id, target_type, target_id, relation_type,
                 generation_method, confidence, evidence_level, review_status, evidence_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON))
                """, item.id(), item.baselineId(), item.sourceType(), item.sourceId(), item.targetType(), item.targetId(),
                item.relationType(), item.generationMethod(), item.confidence(), item.evidenceLevel().name(),
                item.reviewStatus().name(), json(item.evidence()));
    }

    private void saveFinding(Finding item) {
        jdbc.update("""
                INSERT INTO oat_verification_finding
                (id, baseline_id, ac_id, finding_type, perspective, severity, title, description_text,
                 suggestion_text, confidence, evidence_level, verdict, review_status, evidence_json,
                 external_work_item_url, reviewed_by, review_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), ?, ?, ?)
                """, item.id(), item.baselineId(), item.acId(), item.findingType(), item.perspective().name(),
                item.severity().name(), item.title(), item.description(), item.suggestion(), item.confidence(),
                item.evidenceLevel().name(), item.verdict().name(), item.reviewStatus().name(), json(item.evidence()),
                item.externalWorkItemUrl(), item.reviewedBy(), item.reviewReason());
    }

    private AssetSnapshot asset(ResultSet rs, int row) throws SQLException {
        return new AssetSnapshot(rs.getString("id"), rs.getString("project_id"),
                AssetType.valueOf(rs.getString("asset_type")), SourceType.valueOf(rs.getString("source_type")),
                rs.getString("external_id"), rs.getString("external_url"), rs.getString("source_version"),
                rs.getString("file_name"), rs.getString("content_hash"), rs.getString("content_text"),
                map(rs.getString("metadata_json")), Freshness.valueOf(rs.getString("freshness")),
                rs.getString("imported_by"), time(rs.getTimestamp("captured_at")));
    }

    private Baseline baseline(ResultSet rs, int row) throws SQLException {
        return new Baseline(rs.getString("id"), rs.getString("project_id"), rs.getString("name"),
                rs.getString("requirement_asset_id"), rs.getString("testcase_asset_id"),
                rs.getString("source_asset_id"), nullableColumn(rs, "execution_asset_id"),
                nullableColumn(rs, "coverage_asset_id"), rs.getString("source_app_id"),
                rs.getString("repository_url"), rs.getString("source_branch"), rs.getString("source_commit"),
                rs.getString("analyzer_version"), BaselineStatus.valueOf(rs.getString("status")),
                Freshness.valueOf(rs.getString("freshness")), rs.getString("created_by"),
                time(rs.getTimestamp("create_time")), time(rs.getTimestamp("update_time")));
    }

    private AcceptanceCriterion criterion(ResultSet rs, int row) throws SQLException {
        return new AcceptanceCriterion(rs.getString("id"), rs.getString("baseline_id"),
                rs.getString("requirement_key"), rs.getString("ac_key"), rs.getString("title"),
                rs.getString("content_text"), rs.getString("source_locator"), rs.getString("priority"),
                rs.getBoolean("testable"), rs.getBoolean("ambiguity"), rs.getDouble("confidence"));
    }

    private TestcaseProjection testcase(ResultSet rs, int row) throws SQLException {
        return new TestcaseProjection(rs.getString("id"), rs.getString("baseline_id"),
                rs.getString("external_key"), rs.getString("title"), rs.getString("preconditions_text"),
                rs.getString("steps_text"), rs.getString("test_data_text"), rs.getString("expected_text"),
                rs.getString("requirement_refs"), rs.getString("source_locator"));
    }

    private TraceLink traceLink(ResultSet rs, int row) throws SQLException {
        return new TraceLink(rs.getString("id"), rs.getString("baseline_id"), rs.getString("source_type"),
                rs.getString("source_id"), rs.getString("target_type"), rs.getString("target_id"),
                rs.getString("relation_type"), rs.getString("generation_method"), rs.getDouble("confidence"),
                EvidenceLevel.valueOf(rs.getString("evidence_level")), ReviewStatus.valueOf(rs.getString("review_status")),
                map(rs.getString("evidence_json")));
    }

    @SuppressWarnings("unchecked")
    private Finding finding(ResultSet rs, int row) throws SQLException {
        return new Finding(rs.getString("id"), rs.getString("baseline_id"), rs.getString("ac_id"),
                rs.getString("finding_type"), Perspective.valueOf(rs.getString("perspective")),
                Severity.valueOf(rs.getString("severity")), rs.getString("title"), rs.getString("description_text"),
                rs.getString("suggestion_text"), rs.getDouble("confidence"),
                EvidenceLevel.valueOf(rs.getString("evidence_level")), Verdict.valueOf(rs.getString("verdict")),
                ReviewStatus.valueOf(rs.getString("review_status")),
                UtilJson.convertValue(rs.getString("evidence_json"), List.class), rs.getString("external_work_item_url"),
                rs.getString("reviewed_by"), rs.getString("review_reason"));
    }

    private WriteBackAction writeBackAction(ResultSet rs, int row) throws SQLException {
        return new WriteBackAction(rs.getString("id"), rs.getString("project_id"), rs.getString("baseline_id"),
                rs.getString("finding_id"), rs.getString("connector_type"), rs.getString("external_url"),
                rs.getString("status"), rs.getString("message"), rs.getString("created_by"),
                time(rs.getTimestamp("create_time")));
    }

    private String json(Object value) { return UtilJson.writeValueAsString(value == null ? Map.of() : value); }
    @SuppressWarnings("unchecked")
    private Map<String, Object> map(String value) { return value == null ? Map.of() : UtilJson.convertValue(value, Map.class); }
    private Timestamp ts(LocalDateTime value) { return value == null ? null : Timestamp.valueOf(value); }
    private LocalDateTime time(Timestamp value) { return value == null ? null : value.toLocalDateTime(); }

    private String nullableColumn(ResultSet rs, String column) {
        try {
            return rs.getString(column);
        } catch (SQLException ignored) {
            return null;
        }
    }
}
