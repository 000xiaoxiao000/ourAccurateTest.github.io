package com.oAT.web.persistence;

import com.oAT.web.common.UtilJson;
import com.oAT.web.persistence.entity.CaseCenterIndex;
import com.oAT.web.persistence.entity.Usecase;
import com.oAT.web.persistence.entity.UsecaseDirectory;
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
import java.util.stream.StreamSupport;

@Repository
public class CaseCenterRepository {
    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<CaseCenterIndex> rowMapper = this::mapRow;

    public CaseCenterRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<CaseCenterIndex> findById(String id) {
        return queryUnion("id = ?", id).stream().findFirst();
    }

    public List<CaseCenterIndex> findAllById(Iterable<String> ids) {
        List<String> idList = StreamSupport.stream(ids.spliterator(), false).filter(StringUtils::hasText).toList();
        if (idList.isEmpty()) return Collections.emptyList();
        String placeholders = String.join(",", Collections.nCopies(idList.size(), "?"));
        return queryUnion("id IN (" + placeholders + ")", idList.toArray());
    }

    public List<CaseCenterIndex> findByUsecase_ProjectIdAndAndUsecase_Directory(String projectId, String directory, Pageable pageable) {
        return queryPage("usecase", "project_id = ? AND directory_id = ?", pageable, projectId, directory);
    }

    /**
     * A1 需求全系统级：按系统范围过滤用例。appId 为空（兼容旧数据/旧调用）则退化为全项目过滤。
     * 命中规则：主系统 app_id = ? 或关联系统 related_app_ids 包含 ?。
     */
    public List<CaseCenterIndex> findByUsecase_ProjectIdAndUsecase_DirectoryAndAppId(String projectId, String directory, String appId, Pageable pageable) {
        if (!StringUtils.hasText(appId)) {
            return queryPage("usecase", "project_id = ? AND directory_id = ?", pageable, projectId, directory);
        }
        String where = "project_id = ? AND directory_id = ? AND (app_id = ? OR related_app_ids @> ?::jsonb)";
        String sql = "SELECT 'usecase' type, id, payload_json, create_time, update_time FROM oat_usecase WHERE " + where + " ORDER BY create_time DESC";
        List<Object> params = new ArrayList<>(Arrays.asList(projectId, directory, appId, "[\"" + appId + "\"]"));
        if (pageable != null && pageable.isPaged()) {
            sql += " LIMIT ? OFFSET ?";
            params.add(pageable.getPageSize());
            params.add(pageable.getOffset());
        }
        return query(sql, params.toArray());
    }

    public List<CaseCenterIndex> findByUsecase_ProjectId(String projectId) {
        return query("SELECT 'usecase' type, id, payload_json, create_time, update_time FROM oat_usecase WHERE project_id = ? ORDER BY create_time DESC", projectId);
    }

    public List<CaseCenterIndex> findByUsecaseIsNotNull() {
        return query("SELECT 'usecase' type, id, payload_json, create_time, update_time FROM oat_usecase ORDER BY create_time DESC");
    }

    public List<CaseCenterIndex> findByDirectory_ProjectIdAndDirectory_ParentId(String projectId, String parentId) {
        return query("SELECT 'directory' type, id, payload_json, create_time, update_time FROM oat_usecase_directory WHERE project_id = ? AND parent_id = ? ORDER BY create_time DESC", projectId, parentId);
    }

    public List<CaseCenterIndex> findByDirectory_ProjectId(String projectId) {
        return query("SELECT 'directory' type, id, payload_json, create_time, update_time FROM oat_usecase_directory WHERE project_id = ? ORDER BY create_time DESC", projectId);
    }

    @Transactional
    public CaseCenterIndex save(CaseCenterIndex index) {
        normalize(index);
        switch (index.getType()) {
            case "usecase" -> saveUsecase(index);
            case "directory" -> saveDirectory(index);
            default -> throw new IllegalArgumentException("unsupported case center type: " + index.getType());
        }
        return index;
    }

    @Transactional
    public void deleteById(String id) {
        jdbcTemplate.update("DELETE FROM oat_usecase WHERE id = ?", id);
        jdbcTemplate.update("DELETE FROM oat_usecase_directory WHERE id = ?", id);
    }

    private void saveUsecase(CaseCenterIndex index) {
        Usecase u = index.getUsecase();
        String relatedAppIdsJson = (u == null || u.getRelatedAppIds() == null) ? null : UtilJson.writeValueAsString(u.getRelatedAppIds());
        jdbcTemplate.update("""
                        INSERT INTO oat_usecase (id, project_id, directory_id, app_id, related_app_ids, title, content, head_image, defects_json, prd_requirements_json, labels_json, authors_json, payload_json, create_time, update_time)
                        VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?)
                        ON CONFLICT (id) DO UPDATE SET project_id=EXCLUDED.project_id, directory_id=EXCLUDED.directory_id, app_id=EXCLUDED.app_id, related_app_ids=EXCLUDED.related_app_ids, title=EXCLUDED.title, content=EXCLUDED.content, head_image=EXCLUDED.head_image, defects_json=EXCLUDED.defects_json, prd_requirements_json=EXCLUDED.prd_requirements_json, labels_json=EXCLUDED.labels_json, authors_json=EXCLUDED.authors_json, payload_json=EXCLUDED.payload_json, create_time=EXCLUDED.create_time, update_time=EXCLUDED.update_time
                        """, index.getId(), u == null ? null : u.getProjectId(), u == null ? null : u.getDirectory(), u == null ? null : u.getAppId(), relatedAppIdsJson, u == null ? null : u.getTitle(), u == null ? null : u.getContent(), u == null ? null : u.getHeadImage(), u == null ? null : UtilJson.writeValueAsString(u.getDefects()), u == null ? null : UtilJson.writeValueAsString(u.getPrdRequirements()), u == null ? null : UtilJson.writeValueAsString(u.getLabels()), u == null ? null : UtilJson.writeValueAsString(u.getAuthors()), json(index), ts(index.getCreateTime()), ts(index.getUpdateTime()));
    }

    private void saveDirectory(CaseCenterIndex index) {
        UsecaseDirectory d = index.getDirectory();
        jdbcTemplate.update("""
                        INSERT INTO oat_usecase_directory (id, project_id, parent_id, directory_name, payload_json, create_time, update_time)
                        VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
                        ON CONFLICT (id) DO UPDATE SET project_id=EXCLUDED.project_id, parent_id=EXCLUDED.parent_id, directory_name=EXCLUDED.directory_name, payload_json=EXCLUDED.payload_json, create_time=EXCLUDED.create_time, update_time=EXCLUDED.update_time
                        """, index.getId(), d == null ? null : d.getProjectId(), d == null ? null : d.getParentId(), d == null ? null : d.getName(), json(index), ts(index.getCreateTime()), ts(index.getUpdateTime()));
    }

    private List<CaseCenterIndex> queryPage(String type, String where, Pageable pageable, Object... args) {
        Table table = table(type);
        String sql = "SELECT '" + type + "' type, id, payload_json, create_time, update_time FROM " + table.name + " WHERE " + where + " ORDER BY create_time DESC";
        List<Object> params = new ArrayList<>(Arrays.asList(args));
        if (pageable != null && pageable.isPaged()) {
            sql += " LIMIT ? OFFSET ?";
            params.add(pageable.getPageSize());
            params.add(pageable.getOffset());
        }
        return query(sql, params.toArray());
    }

    private List<CaseCenterIndex> queryUnion(String where, Object... args) {
        Object[] params = repeat(args, 2);
        return query("""
                        SELECT * FROM (
                          SELECT 'usecase' type, id, payload_json, create_time, update_time FROM oat_usecase WHERE %s
                          UNION ALL
                          SELECT 'directory' type, id, payload_json, create_time, update_time FROM oat_usecase_directory WHERE %s
                        ) t ORDER BY create_time DESC
                        """.formatted(where, where), params);
    }

    private Object[] repeat(Object[] args, int times) {
        List<Object> params = new ArrayList<>();
        for (int i = 0; i < times; i++) params.addAll(Arrays.asList(args));
        return params.toArray();
    }

    private List<CaseCenterIndex> query(String sql, Object... args) {
        return jdbcTemplate.query(sql, rowMapper, args);
    }

    private void normalize(CaseCenterIndex index) {
        if (!StringUtils.hasText(index.getId())) index.setId(UUID.randomUUID().toString());
        if (!StringUtils.hasText(index.getType())) throw new IllegalArgumentException("case center type must not be empty");
        Date now = new Date();
        if (index.getCreateTime() == null) index.setCreateTime(now);
        if (index.getUpdateTime() == null) index.setUpdateTime(now);
    }

    private CaseCenterIndex mapRow(ResultSet rs, int rowNum) throws SQLException {
        CaseCenterIndex index = UtilJson.convertValue(rs.getString("payload_json"), CaseCenterIndex.class);
        if (index == null) index = new CaseCenterIndex();
        index.setId(rs.getString("id"));
        index.setType(rs.getString("type"));
        index.setCreateTime(toDate(rs.getTimestamp("create_time")));
        index.setUpdateTime(toDate(rs.getTimestamp("update_time")));
        return index;
    }

    private Table table(String type) {
        return switch (type) {
            case "usecase" -> new Table("oat_usecase");
            case "directory" -> new Table("oat_usecase_directory");
            default -> throw new IllegalArgumentException("unsupported case center type: " + type);
        };
    }

    private String json(Object value) { return UtilJson.writeValueAsString(value); }
    private Timestamp ts(Date date) { return date == null ? null : new Timestamp(date.getTime()); }
    private Date toDate(Timestamp timestamp) { return timestamp == null ? null : new Date(timestamp.getTime()); }
    private record Table(String name) {}
}
