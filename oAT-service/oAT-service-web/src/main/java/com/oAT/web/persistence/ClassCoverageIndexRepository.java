package com.oAT.web.persistence;

import com.oAT.web.common.UtilJson;
import com.oAT.web.common.EncryptUtil;
import com.oAT.web.persistence.entity.ClassCoverageIndex;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class ClassCoverageIndexRepository {
    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<ClassCoverageIndex> rowMapper = this::mapRow;

    public ClassCoverageIndexRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ClassCoverageIndex> findByAppId(String appId) {
        return jdbcTemplate.query("""
                        SELECT * FROM oat_class_coverage_index
                        WHERE app_id = ?
                        ORDER BY source_path ASC
                        """,
                rowMapper, appId);
    }

    public List<ClassCoverageIndex> findByReportId(String reportId) {
        return jdbcTemplate.query("""
                        SELECT * FROM oat_class_coverage_index
                        WHERE report_id = ?
                        ORDER BY source_path ASC
                        """,
                rowMapper, reportId);
    }

    @Transactional
    public void replaceReport(String reportId, List<ClassCoverageIndex> indexes) {
        deleteByReportId(reportId);
        if (indexes == null || indexes.isEmpty()) {
            return;
        }
        indexes.forEach(index -> save(reportId, index));
    }

    public void deleteByReportId(String reportId) {
        if (!StringUtils.hasText(reportId)) {
            return;
        }
        jdbcTemplate.update("DELETE FROM oat_class_coverage_index WHERE report_id = ?", reportId);
    }

    private void save(String reportId, ClassCoverageIndex index) {
        normalize(reportId, index);
        jdbcTemplate.update("""
                        INSERT INTO oat_class_coverage_index (
                            id, report_id, app_id, class_name, source_type, language, display_name,
                            source_path, total_methods, covered_methods, total_branches, covered_branches,
                            total_branch_targets, covered_branch_targets, total_lines, covered_lines,
                            total_complexity, line_rate, branch_rate, method_rate, payload_json,
                            create_time, update_time
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                        ON CONFLICT (report_id, source_path) DO UPDATE SET
                            id = EXCLUDED.id,
                            app_id = EXCLUDED.app_id,
                            class_name = EXCLUDED.class_name,
                            source_type = EXCLUDED.source_type,
                            language = EXCLUDED.language,
                            display_name = EXCLUDED.display_name,
                            total_methods = EXCLUDED.total_methods,
                            covered_methods = EXCLUDED.covered_methods,
                            total_branches = EXCLUDED.total_branches,
                            covered_branches = EXCLUDED.covered_branches,
                            total_branch_targets = EXCLUDED.total_branch_targets,
                            covered_branch_targets = EXCLUDED.covered_branch_targets,
                            total_lines = EXCLUDED.total_lines,
                            covered_lines = EXCLUDED.covered_lines,
                            total_complexity = EXCLUDED.total_complexity,
                            line_rate = EXCLUDED.line_rate,
                            branch_rate = EXCLUDED.branch_rate,
                            method_rate = EXCLUDED.method_rate,
                            payload_json = EXCLUDED.payload_json,
                            update_time = EXCLUDED.update_time
                        """,
                index.getId(),
                index.getReportId(),
                index.getAppId(),
                index.getClassName(),
                index.getSourceType(),
                index.getLanguage(),
                index.getDisplayName(),
                index.getSourcePath(),
                index.getTotalMethods(),
                index.getCoveredMethods(),
                index.getTotalBranches(),
                index.getCoveredBranches(),
                index.getTotalBranchTargets(),
                index.getCoveredBranchTargets(),
                index.getTotalLines(),
                index.getCoveredLines(),
                index.getTotalComplexity(),
                index.getLineRate(),
                index.getBranchRate(),
                index.getMethodRate(),
                UtilJson.writeValueAsString(index),
                Timestamp.valueOf(LocalDateTime.now()),
                Timestamp.valueOf(LocalDateTime.now()));
    }

    private void normalize(String reportId, ClassCoverageIndex index) {
        if (!StringUtils.hasText(reportId)) {
            throw new IllegalArgumentException("reportId must not be empty");
        }
        if (index == null || !StringUtils.hasText(index.getAppId())) {
            throw new IllegalArgumentException("coverage appId must not be empty");
        }
        if (!StringUtils.hasText(index.getSourcePath())) {
            throw new IllegalArgumentException("coverage sourcePath must not be empty");
        }
        index.setReportId(reportId);
        index.setId(EncryptUtil.MD5(reportId + ":" + index.getSourcePath()));
        if (!StringUtils.hasText(index.getClassName())) {
            index.setClassName(index.getSourcePath());
        }
        if (!StringUtils.hasText(index.getDisplayName())) {
            index.setDisplayName(index.getSourcePath());
        }
    }

    private ClassCoverageIndex mapRow(ResultSet rs, int rowNum) throws SQLException {
        ClassCoverageIndex index = UtilJson.convertValue(rs.getString("payload_json"), ClassCoverageIndex.class);
        if (index == null) {
            index = new ClassCoverageIndex();
        }
        index.setId(rs.getString("id"));
        index.setReportId(rs.getString("report_id"));
        index.setAppId(rs.getString("app_id"));
        index.setClassName(rs.getString("class_name"));
        index.setSourceType(rs.getString("source_type"));
        index.setLanguage(rs.getString("language"));
        index.setDisplayName(rs.getString("display_name"));
        index.setSourcePath(rs.getString("source_path"));
        index.setTotalMethods(rs.getInt("total_methods"));
        index.setCoveredMethods(rs.getInt("covered_methods"));
        index.setTotalBranches(rs.getInt("total_branches"));
        index.setCoveredBranches(rs.getInt("covered_branches"));
        index.setTotalBranchTargets(rs.getInt("total_branch_targets"));
        index.setCoveredBranchTargets(rs.getInt("covered_branch_targets"));
        index.setTotalLines(rs.getInt("total_lines"));
        index.setCoveredLines(rs.getInt("covered_lines"));
        index.setTotalComplexity(rs.getInt("total_complexity"));
        index.setLineRate(nullableDouble(rs, "line_rate"));
        index.setBranchRate(nullableDouble(rs, "branch_rate"));
        index.setMethodRate(nullableDouble(rs, "method_rate"));
        return index;
    }

    private Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }
}
