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
                 file_name, content_hash, content_text, storage_type, storage_key, content_size,
                 content_preview, metadata_json, freshness, imported_by, captured_at, ai_generated, app_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
                """, asset.id(), asset.projectId(), asset.assetType().name(), asset.sourceType().name(),
                asset.externalId(), asset.externalUrl(), asset.sourceVersion(), asset.fileName(), asset.contentHash(),
                asset.content(), asset.storageType(), asset.storageKey(), asset.contentSize(), asset.contentPreview(),
                json(asset.metadata()), asset.freshness().name(), asset.importedBy(), ts(asset.capturedAt()), asset.aiGenerated(), asset.appId());
    }

    public Optional<AssetSnapshot> findAsset(String projectId, String id) {
        return jdbc.query("SELECT * FROM oat_verification_asset WHERE project_id = ? AND id = ?",
                this::asset, projectId, id).stream().findFirst();
    }

    public List<AssetSnapshot> findAssets(String projectId, AssetType type) {
        return jdbc.query("SELECT * FROM oat_verification_asset WHERE project_id = ? AND asset_type = ? ORDER BY create_time DESC",
                this::asset, projectId, type.name());
    }

    public boolean updateAsset(AssetSnapshot asset) {
        int updated = jdbc.update("""
                UPDATE oat_verification_asset
                SET external_id = ?, external_url = ?, source_version = ?, file_name = ?,
                    content_hash = ?, content_text = ?, storage_type = ?, storage_key = ?,
                    content_size = ?, content_preview = ?, metadata_json = ?::jsonb, freshness = ?, ai_generated = ?, app_id = ?
                WHERE project_id = ? AND id = ?
                """, asset.externalId(), asset.externalUrl(), asset.sourceVersion(), asset.fileName(),
                asset.contentHash(), asset.content(), asset.storageType(), asset.storageKey(), asset.contentSize(),
                asset.contentPreview(), json(asset.metadata()), asset.freshness().name(), asset.aiGenerated(), asset.appId(),
                asset.projectId(), asset.id());
        return updated == 1;
    }

    public boolean deleteAsset(String projectId, String assetId) {
        int updated = jdbc.update("DELETE FROM oat_verification_asset WHERE project_id = ? AND id = ?", projectId, assetId);
        return updated == 1;
    }

    public List<String> findBaselineIdsReferencingAsset(String projectId, String assetId) {
        return jdbc.query("""
                SELECT id FROM oat_verification_baseline WHERE project_id = ?
                AND (requirement_asset_id = ? OR testcase_asset_id = ? OR source_asset_id = ?
                     OR execution_asset_id = ? OR coverage_asset_id = ?)
                """, (rs, row) -> rs.getString("id"), projectId, assetId, assetId, assetId, assetId, assetId);
    }

    public boolean isAssetReferenced(String projectId, String assetId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM oat_verification_baseline
                WHERE project_id = ?
                  AND (requirement_asset_id = ? OR testcase_asset_id = ? OR source_asset_id = ?
                       OR execution_asset_id = ? OR coverage_asset_id = ?)
                """, Integer.class, projectId, assetId, assetId, assetId, assetId, assetId);
        return count != null && count > 0;
    }

    public void saveBaseline(Baseline baseline) {
        jdbc.update("""
                INSERT INTO oat_verification_baseline
                (id, project_id, name, requirement_asset_id, testcase_asset_id, source_asset_id,
                 execution_asset_id, coverage_asset_id, source_app_id,
                 repository_url, source_branch, source_commit, analyzer_version, status, freshness, created_by,
                 create_time, update_time, scope)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, baseline.id(), baseline.projectId(), baseline.name(), baseline.requirementAssetId(),
                baseline.testcaseAssetId(), baseline.sourceAssetId(), baseline.executionAssetId(), baseline.coverageAssetId(),
                baseline.sourceAppId(), baseline.repositoryUrl(), baseline.sourceBranch(), baseline.sourceCommit(),
                baseline.analyzerVersion(), baseline.status().name(), baseline.freshness().name(), baseline.createdBy(),
                ts(baseline.createTime()), ts(baseline.updateTime()), baseline.scope().name());
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

    public boolean updateBaseline(Baseline baseline) {
        int updated = jdbc.update("""
                UPDATE oat_verification_baseline
                SET name = ?, requirement_asset_id = ?, testcase_asset_id = ?, source_asset_id = ?,
                    execution_asset_id = ?, coverage_asset_id = ?, source_app_id = ?,
                    repository_url = ?, source_branch = ?, source_commit = ?,
                    status = ?, freshness = ?, update_time = ?, scope = ?
                WHERE project_id = ? AND id = ?
                """, baseline.name(), baseline.requirementAssetId(), baseline.testcaseAssetId(), baseline.sourceAssetId(),
                baseline.executionAssetId(), baseline.coverageAssetId(), baseline.sourceAppId(),
                baseline.repositoryUrl(), baseline.sourceBranch(), baseline.sourceCommit(),
                baseline.status().name(), baseline.freshness().name(), ts(baseline.updateTime()),
                baseline.scope().name(),
                baseline.projectId(), baseline.id());
        return updated == 1;
    }

    @Transactional
    public boolean deleteBaseline(String projectId, String baselineId) {
        Baseline baseline = findBaseline(projectId, baselineId).orElse(null);
        if (baseline == null) {
            return false;
        }
        deleteAnalysis(baselineId);
        jdbc.update("DELETE FROM oat_verification_baseline WHERE project_id = ? AND id = ?", projectId, baselineId);
        return true;
    }

    public void deleteAnalysis(String baselineId) {
        jdbc.update("DELETE FROM oat_verification_writeback_action WHERE baseline_id = ?", baselineId);
        jdbc.update("DELETE FROM oat_verification_finding WHERE baseline_id = ?", baselineId);
        jdbc.update("DELETE FROM oat_verification_trace_link WHERE baseline_id = ?", baselineId);
        jdbc.update("DELETE FROM oat_verification_ac WHERE baseline_id = ?", baselineId);
        jdbc.update("DELETE FROM oat_verification_testcase WHERE baseline_id = ?", baselineId);
    }

    @Transactional
    public void replaceAnalysis(String baselineId, List<AcceptanceCriterion> criteria,
                                List<TestcaseProjection> testcases, List<TraceLink> links, List<Finding> findings) {
        deleteAnalysis(baselineId);
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
        return jdbc.query("""
                SELECT * FROM oat_verification_finding
                WHERE baseline_id = ?
                ORDER BY CASE severity
                    WHEN 'CRITICAL' THEN 1
                    WHEN 'HIGH' THEN 2
                    WHEN 'MEDIUM' THEN 3
                    WHEN 'LOW' THEN 4
                    WHEN 'INFO' THEN 5
                    ELSE 6
                END, create_time
                """,
                this::finding, baselineId);
    }

    public boolean reviewFinding(String projectId, String findingId, ReviewStatus status, String userId,
                                 String reason, String externalUrl) {
        int updated = jdbc.update("""
                UPDATE oat_verification_finding f
                SET review_status = ?, reviewed_by = ?, review_reason = ?,
                    external_work_item_url = ?, update_time = CURRENT_TIMESTAMP
                FROM oat_verification_baseline b
                WHERE b.project_id = ? AND f.id = ?
                  AND b.id = f.baseline_id
                """, status.name(), userId, reason, externalUrl, projectId, findingId);
        return updated == 1;
    }

    public boolean reviewTraceLink(String projectId, String traceLinkId, ReviewStatus status) {
        int updated = jdbc.update("""
                UPDATE oat_verification_trace_link t
                SET review_status = ?, update_time = CURRENT_TIMESTAMP
                FROM oat_verification_baseline b
                WHERE b.project_id = ? AND t.id = ?
                  AND b.id = t.baseline_id
                """, status.name(), projectId, traceLinkId);
        return updated == 1;
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

    public void saveAnalysisJob(AnalysisJob job) {
        jdbc.update("""
                INSERT INTO oat_verification_analysis_job
                (id, project_id, baseline_id, job_type, input_hash, status, message, created_by,
                 create_time, update_time, finish_time, checkpoint_step, checkpoint_payload, retry_count, max_retries)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """, job.id(), job.projectId(), job.baselineId(), job.jobType(), job.inputHash(),
                job.status().name(), job.message(), job.createdBy(),
                ts(job.createTime()), ts(job.updateTime()), ts(job.finishTime()),
                job.checkpointStep(), json(job.checkpointPayload()), job.retryCount(), job.maxRetries());
    }

    /** Atomically claim the next QUEUED job of the given type. Returns empty if none available. */
    public Optional<AnalysisJob> claimNextPendingJob(String jobType) {
        return jdbc.query("""
                UPDATE oat_verification_analysis_job
                SET status = 'RUNNING', update_time = ?
                WHERE id = (
                    SELECT id FROM oat_verification_analysis_job
                    WHERE job_type = ? AND status = 'QUEUED'
                      AND retry_count < max_retries
                    ORDER BY create_time
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                RETURNING *
                """, this::analysisJob, ts(LocalDateTime.now()), jobType).stream().findFirst();
    }

    /** Persist checkpoint so a resumable job can continue from this step after a restart. */
    public void saveCheckpoint(String jobId, String stepName, Map<String, Object> payload) {
        jdbc.update("""
                UPDATE oat_verification_analysis_job
                SET checkpoint_step = ?, checkpoint_payload = ?::jsonb, update_time = ?
                WHERE id = ?
                """, stepName, json(payload), ts(LocalDateTime.now()), jobId);
    }

    /** Mark a RUNNING job as FAILED and increment retry_count so the scheduler can re-queue it. */
    public void failAndScheduleRetry(String jobId, String errorMessage) {
        jdbc.update("""
                UPDATE oat_verification_analysis_job
                SET status = CASE WHEN retry_count + 1 < max_retries THEN 'QUEUED' ELSE 'FAILED' END,
                    message = ?,
                    retry_count = retry_count + 1,
                    update_time = ?
                WHERE id = ? AND status = 'RUNNING'
                """, errorMessage, ts(LocalDateTime.now()), jobId);
    }

    /** Find an existing active (non-terminal) job for idempotency check. */
    public Optional<AnalysisJob> findActiveJobByInputHash(String baselineId, String jobType, String inputHash) {
        return jdbc.query("""
                SELECT * FROM oat_verification_analysis_job
                WHERE baseline_id = ? AND job_type = ? AND input_hash = ?
                  AND status NOT IN ('SUCCEEDED', 'FAILED')
                ORDER BY create_time DESC LIMIT 1
                """, this::analysisJob, baselineId, jobType, inputHash).stream().findFirst();
    }

    /** Find a successfully completed job — used for cache-hit check to skip re-computation. */
    public Optional<AnalysisJob> findSucceededJobByInputHash(String baselineId, String jobType, String inputHash) {
        return jdbc.query("""
                SELECT * FROM oat_verification_analysis_job
                WHERE baseline_id = ? AND job_type = ? AND input_hash = ? AND status = 'SUCCEEDED'
                ORDER BY create_time DESC LIMIT 1
                """, this::analysisJob, baselineId, jobType, inputHash).stream().findFirst();
    }

    public Optional<AnalysisJob> findAnalysisJob(String projectId, String jobId) {
        return jdbc.query("""
                SELECT * FROM oat_verification_analysis_job
                WHERE project_id = ? AND id = ?
                """, this::analysisJob, projectId, jobId).stream().findFirst();
    }

    public Optional<AnalysisJob> findRunningAnalysisJob(String projectId, String baselineId) {
        return jdbc.query("""
                SELECT * FROM oat_verification_analysis_job
                WHERE project_id = ? AND baseline_id = ? AND status IN ('QUEUED','RUNNING')
                ORDER BY create_time DESC LIMIT 1
                """, this::analysisJob, projectId, baselineId).stream().findFirst();
    }

    public Optional<AnalysisJob> findLatestAnalysisJob(String projectId, String baselineId) {
        return jdbc.query("""
                SELECT * FROM oat_verification_analysis_job
                WHERE project_id = ? AND baseline_id = ?
                ORDER BY create_time DESC LIMIT 1
                """, this::analysisJob, projectId, baselineId).stream().findFirst();
    }

    public int failStaleAnalysisJobs(String projectId, String baselineId, LocalDateTime cutoffTime, String message) {
        return jdbc.update("""
                UPDATE oat_verification_analysis_job
                SET status = 'FAILED', message = ?, update_time = ?, finish_time = ?
                WHERE project_id = ? AND baseline_id = ? AND status IN ('QUEUED','RUNNING') AND update_time < ?
                """, message, ts(LocalDateTime.now()), ts(LocalDateTime.now()), projectId, baselineId, ts(cutoffTime));
    }

    public int failActiveAnalysisJobs(String projectId, String baselineId, String message) {
        return jdbc.update("""
                UPDATE oat_verification_analysis_job
                SET status = 'FAILED', message = ?, update_time = ?, finish_time = ?
                WHERE project_id = ? AND baseline_id = ? AND status IN ('QUEUED','RUNNING')
                """, message, ts(LocalDateTime.now()), ts(LocalDateTime.now()), projectId, baselineId);
    }

    public void updateAnalysisJobStatus(String jobId, AnalysisJobStatus status, String message, LocalDateTime finishTime) {
        jdbc.update("""
                UPDATE oat_verification_analysis_job
                SET status = ?, message = ?, update_time = ?, finish_time = ?
                WHERE id = ?
                """, status.name(), message, ts(LocalDateTime.now()), ts(finishTime), jobId);
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
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
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
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
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
                nullableColumn(rs, "storage_type"), nullableColumn(rs, "storage_key"),
                longColumn(rs, "content_size"), nullableColumn(rs, "content_preview"),
                map(rs.getString("metadata_json")), Freshness.valueOf(rs.getString("freshness")),
                rs.getString("imported_by"), time(rs.getTimestamp("captured_at")), rs.getBoolean("ai_generated"),
                rs.getString("app_id"));
    }

    private Baseline baseline(ResultSet rs, int row) throws SQLException {
        String scopeText = nullableColumn(rs, "scope");
        BaselineScope scope = scopeText == null ? BaselineScope.SYSTEM : BaselineScope.valueOf(scopeText);
        return new Baseline(rs.getString("id"), rs.getString("project_id"), rs.getString("name"),
                rs.getString("requirement_asset_id"), rs.getString("testcase_asset_id"),
                rs.getString("source_asset_id"), nullableColumn(rs, "execution_asset_id"),
                nullableColumn(rs, "coverage_asset_id"), rs.getString("source_app_id"),
                rs.getString("repository_url"), rs.getString("source_branch"), rs.getString("source_commit"),
                rs.getString("analyzer_version"), BaselineStatus.valueOf(rs.getString("status")),
                Freshness.valueOf(rs.getString("freshness")), rs.getString("created_by"),
                time(rs.getTimestamp("create_time")), time(rs.getTimestamp("update_time")),
                nullableColumn(rs, "static_graph_version"), nullableColumn(rs, "runtime_graph_version"),
                nullableColumn(rs, "cfg_hash"), nullableColumn(rs, "dependency_hash"),
                nullableColumn(rs, "coverage_report_hash"), nullableColumn(rs, "execution_trace_hash"),
                nullableColumn(rs, "symbol_hash"), nullableColumn(rs, "superseded_by_baseline_id"),
                scope);
    }

    /** Update graph snapshot version fields on a baseline after projection completes. */
    public void updateBaselineGraphVersions(String baselineId,
                                            String staticGraphVersion, String runtimeGraphVersion,
                                            String cfgHash, String dependencyHash,
                                            String coverageReportHash, String executionTraceHash,
                                            String symbolHash) {
        jdbc.update("""
                UPDATE oat_verification_baseline
                SET static_graph_version = ?, runtime_graph_version = ?,
                    cfg_hash = ?, dependency_hash = ?,
                    coverage_report_hash = ?, execution_trace_hash = ?,
                    symbol_hash = ?, update_time = ?
                WHERE id = ?
                """, staticGraphVersion, runtimeGraphVersion, cfgHash, dependencyHash,
                coverageReportHash, executionTraceHash, symbolHash,
                ts(java.time.LocalDateTime.now()), baselineId);
    }

    /** Mark a baseline as superseded by a newer one. */
    public void markBaselineSuperseded(String baselineId, String supersededByBaselineId) {
        jdbc.update("""
                UPDATE oat_verification_baseline
                SET superseded_by_baseline_id = ?, status = 'STALE', update_time = ?
                WHERE id = ?
                """, supersededByBaselineId, ts(java.time.LocalDateTime.now()), baselineId);
    }

    /** Detect runtime executions whose source_commit differs from the baseline's source_commit. */
    public List<StaleRuntimeExecution> findStaleRuntimeExecutions(String baselineId) {
        return jdbc.query("""
                SELECT * FROM v_stale_runtime_evidence WHERE baseline_id = ?
                """, (rs, row) -> new StaleRuntimeExecution(
                        rs.getString("baseline_id"), rs.getString("project_id"),
                        rs.getString("expected_commit"), rs.getString("actual_commit"),
                        rs.getString("execution_id"),
                        rs.getTimestamp("captured_at") == null ? null
                                : rs.getTimestamp("captured_at").toInstant().atOffset(java.time.ZoneOffset.UTC)),
                baselineId);
    }

    public record StaleRuntimeExecution(
            String baselineId, String projectId,
            String expectedCommit, String actualCommit,
            String executionId, java.time.OffsetDateTime capturedAt) {}

    /** Persist a runtime test execution audit row. */
    public void saveRuntimeTestExecution(RuntimeTestExecution e) {
        jdbc.update("""
                INSERT INTO oat_runtime_test_execution
                (id, project_id, baseline_id, source_commit, external_execution_id,
                 testcase_key, environment, collector_version, trace_hash, input_hash,
                 started_at, finished_at, captured_at, attributes_json)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb)
                ON CONFLICT (baseline_id, external_execution_id) DO UPDATE
                SET trace_hash = EXCLUDED.trace_hash, attributes_json = EXCLUDED.attributes_json
                """,
                e.id(), e.projectId(), e.baselineId(), e.sourceCommit(), e.externalExecutionId(),
                e.testcaseKey(), e.environment(), e.collectorVersion(), e.traceHash(), e.inputHash(),
                e.startedAt() == null ? null : java.sql.Timestamp.from(e.startedAt().toInstant()),
                e.finishedAt() == null ? null : java.sql.Timestamp.from(e.finishedAt().toInstant()),
                e.capturedAt() == null ? null : java.sql.Timestamp.from(e.capturedAt().toInstant()),
                json(e.attributes()));
    }

    public record RuntimeTestExecution(
            String id, String projectId, String baselineId, String sourceCommit,
            String externalExecutionId, String testcaseKey, String environment,
            String collectorVersion, String traceHash, String inputHash,
            java.time.OffsetDateTime startedAt, java.time.OffsetDateTime finishedAt,
            java.time.OffsetDateTime capturedAt, java.util.Map<String, Object> attributes) {}

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

    private AnalysisJob analysisJob(ResultSet rs, int row) throws SQLException {
        return new AnalysisJob(rs.getString("id"), rs.getString("project_id"), rs.getString("baseline_id"),
                nullableColumn(rs, "job_type"), nullableColumn(rs, "input_hash"),
                AnalysisJobStatus.valueOf(rs.getString("status")), rs.getString("message"), rs.getString("created_by"),
                time(rs.getTimestamp("create_time")), time(rs.getTimestamp("update_time")),
                time(rs.getTimestamp("finish_time")),
                nullableColumn(rs, "checkpoint_step"),
                map(nullableColumn(rs, "checkpoint_payload")),
                rs.getInt("retry_count"), rs.getInt("max_retries"));
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

    private long longColumn(ResultSet rs, String column) {
        try {
            long value = rs.getLong(column);
            return rs.wasNull() ? 0 : value;
        } catch (SQLException ignored) {
            return 0;
        }
    }
}
