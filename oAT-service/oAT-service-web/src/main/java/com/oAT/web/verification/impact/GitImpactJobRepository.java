package com.oAT.web.verification.impact;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public class GitImpactJobRepository {
    private final JdbcTemplate jdbc;

    public GitImpactJobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void create(GitImpactJob job) {
        jdbc.update("""
                INSERT INTO oat_git_impact_job
                (id, project_id, baseline_id, app_id, base_commit, head_commit, created_by, status, stage, percent,
                 message, result_json, error_message, create_time, update_time, finish_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                """, job.id(), job.projectId(), job.baselineId(), job.appId(), job.baseCommit(), job.headCommit(),
                job.createdBy(), job.status(), job.stage(), job.percent(), job.message(), job.resultJson(), job.error(),
                ts(job.createdAt()), ts(job.updatedAt()), ts(job.finishedAt()));
    }

    public Optional<GitImpactJob> find(String projectId, String id) {
        return jdbc.query("SELECT * FROM oat_git_impact_job WHERE project_id = ? AND id = ?", this::map, projectId, id)
                .stream().findFirst();
    }

    public Optional<GitImpactJob> findActive(String projectId, String baselineId, String appId,
                                              String baseCommit, String headCommit) {
        return jdbc.query("""
                SELECT * FROM oat_git_impact_job
                WHERE project_id = ? AND baseline_id = ? AND app_id = ?
                  AND base_commit = ? AND head_commit = ?
                  AND status IN ('PENDING', 'RUNNING')
                ORDER BY create_time DESC LIMIT 1
                """, this::map, projectId, baselineId, appId, baseCommit, headCommit).stream().findFirst();
    }

    public Optional<GitImpactJob> claimNext() {
        return jdbc.query("""
                UPDATE oat_git_impact_job
                SET status = 'RUNNING', stage = 'STARTING', percent = 3,
                    message = '正在启动 Git 影响分析', update_time = CURRENT_TIMESTAMP
                WHERE id = (
                    SELECT id FROM oat_git_impact_job
                    WHERE status = 'PENDING'
                    ORDER BY create_time
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                RETURNING *
                """, this::map).stream().findFirst();
    }

    public void requeueStaleRunningJobs(LocalDateTime before) {
        jdbc.update("""
                UPDATE oat_git_impact_job
                SET status = 'PENDING', stage = 'RECOVERING', percent = 1,
                    message = '服务重启后恢复任务', update_time = CURRENT_TIMESTAMP
                WHERE status = 'RUNNING' AND update_time < ?
                """, ts(before));
    }

    public void updateProgress(String id, String status, String stage, int percent, String message,
                               String resultJson, String error, LocalDateTime finishedAt) {
        jdbc.update("""
                UPDATE oat_git_impact_job
                SET status = ?, stage = ?, percent = ?, message = ?,
                    result_json = COALESCE(?::jsonb, result_json), error_message = ?,
                    update_time = CURRENT_TIMESTAMP, finish_time = ?
                WHERE id = ?
                """, status, stage, percent, message, resultJson, error, ts(finishedAt), id);
    }

    private GitImpactJob map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new GitImpactJob(rs.getString("id"), rs.getString("project_id"), rs.getString("baseline_id"),
                rs.getString("app_id"), rs.getString("base_commit"), rs.getString("head_commit"),
                rs.getString("created_by"), rs.getString("status"), rs.getString("stage"), rs.getInt("percent"),
                rs.getString("message"), rs.getString("result_json"), rs.getString("error_message"),
                time(rs, "create_time"), time(rs, "update_time"), time(rs, "finish_time"));
    }

    private Timestamp ts(LocalDateTime value) { return value == null ? null : Timestamp.valueOf(value); }
    private LocalDateTime time(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    public record GitImpactJob(String id, String projectId, String baselineId, String appId, String baseCommit,
                               String headCommit, String createdBy, String status, String stage, int percent,
                               String message, String resultJson, String error, LocalDateTime createdAt,
                               LocalDateTime updatedAt, LocalDateTime finishedAt) { }
}
