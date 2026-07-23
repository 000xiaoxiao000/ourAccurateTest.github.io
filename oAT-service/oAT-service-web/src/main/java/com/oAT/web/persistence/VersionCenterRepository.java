package com.oAT.web.persistence;

import com.oAT.web.common.UtilJson;
import com.oAT.web.persistence.entity.VersionCenterIndex;
import com.oAT.web.persistence.entity.VersionCompareReport;
import com.oAT.web.persistence.entity.VersionItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;

@Repository
public class VersionCenterRepository {
    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<VersionCenterIndex> rowMapper = this::mapRow;

    public VersionCenterRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<VersionCenterIndex> findById(String id) {
        return queryUnion("id = ?", id).stream().findFirst();
    }

    public List<VersionCenterIndex> findByVersionItem_ProjectIdAndVersionItem_AppId(String projectId, String appId) {
        return queryVersion("project_id = ? AND app_id = ?", "create_time DESC", projectId, appId);
    }

    public Page<VersionCenterIndex> findByVersionItem_ProjectIdAndVersionItem_AppId(String projectId, String appId, Pageable pageable) {
        return queryVersionPage("project_id = ? AND app_id = ?", pageable, projectId, appId);
    }

    public List<VersionCenterIndex> findByCompareReport_ProjectIdAndCompareReport_AppId(String projectId, String appId, Pageable pageable) {
        return queryComparePage("project_id = ? AND app_id = ?", pageable, projectId, appId).getContent();
    }

    public Page<VersionCenterIndex> findCompareReportPage(String projectId, String appId, Pageable pageable) {
        return queryComparePage("project_id = ? AND app_id = ?", pageable, projectId, appId);
    }

    public List<VersionCenterIndex> findTop1ByVersionItem_AppIdAndVersionItem_VersionNumber(String appId, String versionNumber) {
        return queryVersionTop1("app_id = ? AND version_number = ?", appId, versionNumber);
    }

    public List<VersionCenterIndex> findTop1ByVersionItem_ProjectIdAndVersionItem_AppIdOrderByCreateTimeDesc(String projectId, String appId) {
        return queryVersionTop1("project_id = ? AND app_id = ?", projectId, appId);
    }

    public List<VersionCenterIndex> findTop1ByVersionItem_AppIdAndVersionItem_RepoBranchAndVersionItem_RepoCommitId(String appId, String repoBranch, String repoCommitId) {
        return queryVersionTop1("app_id = ? AND repo_branch = ? AND repo_commit_id = ?", appId, repoBranch, repoCommitId);
    }

    public List<VersionCenterIndex> findTop1ByVersionItem_AppIdAndVersionItem_RepoCommitId(String appId, String repoCommitId) {
        return queryVersionTop1("app_id = ? AND repo_commit_id = ?", appId, repoCommitId);
    }

    public List<VersionCenterIndex> findByVersionItem_AppIdAndVersionItem_RepoBranch(String appId, String repoBranch) {
        return queryVersion("app_id = ? AND repo_branch = ?", "create_time DESC", appId, repoBranch);
    }

    public List<VersionCenterIndex> findTop1ByVersionItem_AppIdOrderByCreateTimeDesc(String appId) {
        return queryVersionTop1("app_id = ?", appId);
    }

    public List<VersionCenterIndex> findTop1ByVersionItem_AppIdAndVersionItem_VersionNumberAndVersionItem_RepoBranchAndVersionItem_RepoCommitId(String appId, String versionNumber, String repoBranch, String repoCommitId) {
        return queryVersionTop1("app_id = ? AND version_number = ? AND repo_branch = ? AND repo_commit_id = ?", appId, versionNumber, repoBranch, repoCommitId);
    }

    @Transactional
    public VersionCenterIndex save(VersionCenterIndex index) {
        normalize(index);
        if ("versionItem".equals(index.getType())) saveVersion(index);
        else if ("compareReport".equals(index.getType())) saveCompareReport(index);
        else throw new IllegalArgumentException("unsupported version center type: " + index.getType());
        return index;
    }

    @Transactional
    public void deleteById(String id) {
        jdbcTemplate.update("DELETE FROM oat_version WHERE id = ?", id);
        jdbcTemplate.update("DELETE FROM oat_version_compare_report WHERE id = ?", id);
    }

    private void saveVersion(VersionCenterIndex index) {
        VersionItem item = index.getVersionItem();
        jdbcTemplate.update("""
                        INSERT INTO oat_version (id, project_id, app_id, version_number, repo_branch, repo_commit_id, source_type, payload_json, create_time, update_time)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                        ON CONFLICT (id) DO UPDATE SET project_id=EXCLUDED.project_id, app_id=EXCLUDED.app_id, version_number=EXCLUDED.version_number, repo_branch=EXCLUDED.repo_branch, repo_commit_id=EXCLUDED.repo_commit_id, source_type=EXCLUDED.source_type, payload_json=EXCLUDED.payload_json, create_time=EXCLUDED.create_time, update_time=EXCLUDED.update_time
                        """, index.getId(), item == null ? null : item.getProjectId(), item == null ? null : item.getAppId(), item == null ? null : item.getVersionNumber(), item == null ? null : item.getRepoBranch(), item == null ? null : item.getRepoCommitId(), item == null ? null : item.getSourceType(), json(index), ts(index.getCreateTime()), ts(index.getUpdateTime()));
    }

    private void saveCompareReport(VersionCenterIndex index) {
        VersionCompareReport report = index.getCompareReport();
        jdbcTemplate.update("""
                        INSERT INTO oat_version_compare_report (id, project_id, app_id, job_id, job_name, job_log, source_version, target_version, git_branch, git_old_commit, git_new_commit, add_class_count, update_class_count, delete_class_count, add_method_count, update_method_count, delete_method_count, impact_case_count, differences_json, cases_json, payload_json, create_time, update_time)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?)
                        ON CONFLICT (id) DO UPDATE SET project_id=EXCLUDED.project_id, app_id=EXCLUDED.app_id, job_id=EXCLUDED.job_id, job_name=EXCLUDED.job_name, job_log=EXCLUDED.job_log, source_version=EXCLUDED.source_version, target_version=EXCLUDED.target_version, git_branch=EXCLUDED.git_branch, git_old_commit=EXCLUDED.git_old_commit, git_new_commit=EXCLUDED.git_new_commit, add_class_count=EXCLUDED.add_class_count, update_class_count=EXCLUDED.update_class_count, delete_class_count=EXCLUDED.delete_class_count, add_method_count=EXCLUDED.add_method_count, update_method_count=EXCLUDED.update_method_count, delete_method_count=EXCLUDED.delete_method_count, impact_case_count=EXCLUDED.impact_case_count, differences_json=EXCLUDED.differences_json, cases_json=EXCLUDED.cases_json, payload_json=EXCLUDED.payload_json, create_time=EXCLUDED.create_time, update_time=EXCLUDED.update_time
                        """, index.getId(), report == null ? null : report.getProjectId(), report == null ? null : report.getAppId(), report == null ? null : report.getJobId(), report == null ? null : report.getJobName(), report == null ? null : report.getJobLog(), report == null ? null : report.getSourceVersion(), report == null ? null : report.getTargetVersion(), report == null ? null : report.getGitBranch(), report == null ? null : report.getGitOldCommit(), report == null ? null : report.getGitNewCommit(), report == null ? null : report.getAddClassCount(), report == null ? null : report.getUpdateClassCount(), report == null ? null : report.getDeleteClassCount(), report == null ? null : report.getAddMethodCount(), report == null ? null : report.getUpdateMethodCount(), report == null ? null : report.getDeleteMethodCount(), report == null ? null : report.getImpactCaseCount(), report == null ? null : UtilJson.writeValueAsString(report.getDifferences()), report == null ? null : UtilJson.writeValueAsString(report.getCases()), json(index), ts(index.getCreateTime()), ts(index.getUpdateTime()));
    }

    private List<VersionCenterIndex> queryVersionTop1(String where, Object... args) {
        return jdbcTemplate.query("SELECT 'versionItem' type, id, payload_json, create_time, update_time FROM oat_version WHERE " + where + " ORDER BY create_time DESC LIMIT 1", rowMapper, args);
    }

    private List<VersionCenterIndex> queryVersion(String where, String orderBy, Object... args) {
        return jdbcTemplate.query("SELECT 'versionItem' type, id, payload_json, create_time, update_time FROM oat_version WHERE " + where + " ORDER BY " + orderBy, rowMapper, args);
    }

    private Page<VersionCenterIndex> queryVersionPage(String where, Pageable pageable, Object... args) {
        return queryPage("oat_version", "versionItem", where, pageable, args);
    }

    private Page<VersionCenterIndex> queryComparePage(String where, Pageable pageable, Object... args) {
        return queryPage("oat_version_compare_report", "compareReport", where, pageable, args);
    }

    private Page<VersionCenterIndex> queryPage(String table, String type, String where, Pageable pageable, Object... args) {
        List<Object> parameters = new ArrayList<>(Arrays.asList(args));
        String sql = "SELECT '" + type + "' type, id, payload_json, create_time, update_time FROM " + table + " WHERE " + where + " ORDER BY create_time DESC";
        if (pageable != null && pageable.isPaged()) {
            sql += " LIMIT ? OFFSET ?";
            parameters.add(pageable.getPageSize());
            parameters.add(pageable.getOffset());
        }
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM " + table + " WHERE " + where, Long.class, args);
        List<VersionCenterIndex> content = jdbcTemplate.query(sql, rowMapper, parameters.toArray());
        return new PageImpl<>(content, pageable == null ? Pageable.unpaged() : pageable, total == null ? 0 : total);
    }

    private List<VersionCenterIndex> queryUnion(String where, Object... args) {
        Object[] params = repeat(args, 2);
        return jdbcTemplate.query("""
                        SELECT * FROM (
                          SELECT 'versionItem' type, id, payload_json, create_time, update_time FROM oat_version WHERE %s
                          UNION ALL
                          SELECT 'compareReport' type, id, payload_json, create_time, update_time FROM oat_version_compare_report WHERE %s
                        ) t ORDER BY create_time DESC
                        """.formatted(where, where), rowMapper, params);
    }

    private Object[] repeat(Object[] args, int times) {
        List<Object> params = new ArrayList<>();
        for (int i = 0; i < times; i++) params.addAll(Arrays.asList(args));
        return params.toArray();
    }

    private void normalize(VersionCenterIndex index) {
        if (!StringUtils.hasText(index.getId())) index.setId(UUID.randomUUID().toString());
        if (!StringUtils.hasText(index.getType())) throw new IllegalArgumentException("version center type must not be empty");
        Date now = new Date();
        if (index.getCreateTime() == null) index.setCreateTime(now);
        if (index.getUpdateTime() == null) index.setUpdateTime(now);
    }

    private VersionCenterIndex mapRow(ResultSet rs, int rowNum) throws SQLException {
        VersionCenterIndex index = UtilJson.convertValue(rs.getString("payload_json"), VersionCenterIndex.class);
        if (index == null) index = new VersionCenterIndex();
        index.setId(rs.getString("id"));
        index.setType(rs.getString("type"));
        index.setCreateTime(toDate(rs.getTimestamp("create_time")));
        index.setUpdateTime(toDate(rs.getTimestamp("update_time")));
        return index;
    }

    private String json(Object value) { return UtilJson.writeValueAsString(value); }
    private Timestamp ts(Date date) { return date == null ? null : new Timestamp(date.getTime()); }
    private Date toDate(Timestamp timestamp) { return timestamp == null ? null : new Date(timestamp.getTime()); }
}
