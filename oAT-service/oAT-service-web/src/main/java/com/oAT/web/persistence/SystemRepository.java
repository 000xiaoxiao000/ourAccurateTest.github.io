package com.oAT.web.persistence;

import com.oAT.web.common.UtilJson;
import com.oAT.web.persistence.entity.*;
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
public class SystemRepository {
    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<SystemIndex> rowMapper = this::mapRow;

    public SystemRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<SystemIndex> findById(String id) {
        return queryUnion("id = ?", null, id).stream().findFirst();
    }

    public boolean existsById(String id) {
        Integer count = jdbcTemplate.queryForObject("""
                        SELECT SUM(cnt) FROM (
                          SELECT COUNT(1) cnt FROM oat_user WHERE id = ?
                          UNION ALL SELECT COUNT(1) FROM oat_project WHERE id = ?
                          UNION ALL SELECT COUNT(1) FROM oat_app WHERE id = ?
                          UNION ALL SELECT COUNT(1) FROM oat_label_group WHERE id = ?
                          UNION ALL SELECT COUNT(1) FROM oat_project_member WHERE id = ?
                          UNION ALL SELECT COUNT(1) FROM oat_system_log WHERE id = ?
                        ) t
                        """,
                Integer.class, id, id, id, id, id, id);
        return count != null && count > 0;
    }

    public List<SystemIndex> findAllById(Iterable<String> ids) {
        List<String> idList = StreamSupport.stream(ids.spliterator(), false).filter(StringUtils::hasText).toList();
        if (idList.isEmpty()) return Collections.emptyList();
        String placeholders = String.join(",", Collections.nCopies(idList.size(), "?"));
        return queryUnion("id IN (" + placeholders + ")", null, idList.toArray());
    }

    public List<SystemIndex> findByUserNameOrUserEmail(String name, String email) {
        return query("SELECT 'user' type, id, payload_json, create_time, update_time FROM oat_user WHERE name = ? OR email = ? ORDER BY update_time DESC", name, email);
    }

    public List<SystemIndex> findByProjectMember_MemberId(String memberId, Pageable pageable) {
        return queryPage("projectMember", "member_id = ?", pageable, memberId);
    }

    public List<SystemIndex> findByAppCreateProjectIdOrAppRange(String createProjectId, String range) {
        return query("SELECT 'app' type, id, payload_json, create_time, update_time FROM oat_app WHERE project_id = ? OR range_type = ? ORDER BY update_time DESC", createProjectId, range);
    }

    public List<SystemIndex> findByLabelGroup_ProjectidAndLabelGroup_Type(String projectId, String type) {
        return query("SELECT 'labelGroup' type, id, payload_json, create_time, update_time FROM oat_label_group WHERE project_id = ? AND label_type = ? ORDER BY update_time DESC", projectId, type);
    }

    public List<SystemIndex> findByProjectMember_ProjectId(String projectId, Pageable pageable) {
        return queryPage("projectMember", "project_id = ?", pageable, projectId);
    }

    public List<SystemIndex> findByProjectMember_ProjectIdAndProjectMember_MemberId(String projectId, String memberId) {
        return query("SELECT 'projectMember' type, id, payload_json, create_time, update_time FROM oat_project_member WHERE project_id = ? AND member_id = ? ORDER BY update_time DESC", projectId, memberId);
    }

    public List<SystemIndex> findByType(String type, Pageable pageable) {
        return queryPage(type, "1 = 1", pageable);
    }

    public List<SystemIndex> findBySystemLog_ProjectId(String projectId, Pageable pageable) {
        return queryPage("systemLog", "project_id = ?", pageable, projectId);
    }

    public List<SystemIndex> findAll() {
        return queryUnion("1 = 1", "update_time DESC");
    }

    @Transactional
    public SystemIndex save(SystemIndex index) {
        normalize(index);
        switch (index.getType()) {
            case "user" -> saveUser(index);
            case "project" -> saveProject(index);
            case "app" -> saveApp(index);
            case "labelGroup" -> saveLabelGroup(index);
            case "projectMember" -> saveProjectMember(index);
            case "systemLog" -> saveSystemLog(index);
            default -> throw new IllegalArgumentException("unsupported system index type: " + index.getType());
        }
        return index;
    }

    @Transactional
    public void deleteById(String id) {
        jdbcTemplate.update("DELETE FROM oat_user WHERE id = ?", id);
        jdbcTemplate.update("DELETE FROM oat_project WHERE id = ?", id);
        jdbcTemplate.update("DELETE FROM oat_app WHERE id = ?", id);
        jdbcTemplate.update("DELETE FROM oat_label_group WHERE id = ?", id);
        jdbcTemplate.update("DELETE FROM oat_project_member WHERE id = ?", id);
        jdbcTemplate.update("DELETE FROM oat_system_log WHERE id = ?", id);
    }

    @Transactional
    public void deleteAll() {
        jdbcTemplate.update("DELETE FROM oat_user");
        jdbcTemplate.update("DELETE FROM oat_project");
        jdbcTemplate.update("DELETE FROM oat_app");
        jdbcTemplate.update("DELETE FROM oat_label_group");
        jdbcTemplate.update("DELETE FROM oat_project_member");
        jdbcTemplate.update("DELETE FROM oat_system_log");
    }

    @Transactional
    public void deleteAll(Iterable<SystemIndex> indexes) {
        if (indexes == null) return;
        for (SystemIndex index : indexes) {
            if (index != null && StringUtils.hasText(index.getId())) deleteById(index.getId());
        }
    }

    private void saveUser(SystemIndex index) {
        User user = index.getUser();
        jdbcTemplate.update("""
                        INSERT INTO oat_user (id, name, nick_name, email, password, header, phone, readme, payload_json, create_time, update_time)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                        ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, nick_name=EXCLUDED.nick_name, email=EXCLUDED.email, password=EXCLUDED.password, header=EXCLUDED.header, phone=EXCLUDED.phone, readme=EXCLUDED.readme, payload_json=EXCLUDED.payload_json, create_time=EXCLUDED.create_time, update_time=EXCLUDED.update_time
                        """, index.getId(), user == null ? null : user.getName(), user == null ? null : user.getNickName(), user == null ? null : user.getEmail(), user == null ? null : user.getPassword(), user == null ? null : user.getHeader(), user == null ? null : user.getPhone(), user == null ? null : user.getReadme(), json(index), ts(index.getCreateTime()), ts(index.getUpdateTime()));
    }

    private void saveProject(SystemIndex index) {
        Project project = index.getProject();
        jdbcTemplate.update("""
                        INSERT INTO oat_project (id, name, project_describe, owner_id, payload_json, create_time, update_time)
                        VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
                        ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, project_describe=EXCLUDED.project_describe, owner_id=EXCLUDED.owner_id, payload_json=EXCLUDED.payload_json, create_time=EXCLUDED.create_time, update_time=EXCLUDED.update_time
                        """, index.getId(), project == null ? null : project.getName(), project == null ? null : project.getDescribe(), project == null ? null : project.getCreate(), json(index), ts(index.getCreateTime()), ts(index.getUpdateTime()));
    }

    private void saveApp(SystemIndex index) {
        App app = index.getApp();
        jdbcTemplate.update("""
                        INSERT INTO oat_app (id, name, project_id, range_type, src_name, language, language_config_json, app_describe, properties_text, current_version, current_branch, current_commit_id, repo_address, repo_user_name, repo_password, create_user_id, payload_json, create_time, update_time)
                        VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                        ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, project_id=EXCLUDED.project_id, range_type=EXCLUDED.range_type, src_name=EXCLUDED.src_name, language=EXCLUDED.language, language_config_json=EXCLUDED.language_config_json, app_describe=EXCLUDED.app_describe, properties_text=EXCLUDED.properties_text, current_version=EXCLUDED.current_version, current_branch=EXCLUDED.current_branch, current_commit_id=EXCLUDED.current_commit_id, repo_address=EXCLUDED.repo_address, repo_user_name=EXCLUDED.repo_user_name, repo_password=EXCLUDED.repo_password, create_user_id=EXCLUDED.create_user_id, payload_json=EXCLUDED.payload_json, create_time=EXCLUDED.create_time, update_time=EXCLUDED.update_time
                        """, index.getId(), app == null ? null : app.getName(), app == null ? null : app.getCreateProjectId(), app == null ? null : app.getRange(), app == null ? null : app.getSrcName(), app == null ? null : app.getLanguage(), app == null ? null : app.getLanguageConfig(), app == null ? null : app.getDescribe(), app == null ? null : app.getProperties(), app == null ? null : app.getCurrentVersion(), app == null ? null : app.getCurrentBranch(), app == null ? null : app.getCurrentCommitId(), app == null ? null : app.getRepoAddress(), app == null ? null : app.getRepoUserName(), app == null ? null : app.getRepoPassword(), app == null ? null : app.getCreateUserId(), json(index), ts(index.getCreateTime()), ts(index.getUpdateTime()));
    }

    private void saveLabelGroup(SystemIndex index) {
        LabelGroup labelGroup = index.getLabelGroup();
        jdbcTemplate.update("""
                        INSERT INTO oat_label_group (id, project_id, label_type, payload_json, create_time, update_time)
                        VALUES (?, ?, ?, ?::jsonb, ?, ?)
                        ON CONFLICT (id) DO UPDATE SET project_id=EXCLUDED.project_id, label_type=EXCLUDED.label_type, payload_json=EXCLUDED.payload_json, create_time=EXCLUDED.create_time, update_time=EXCLUDED.update_time
                        """, index.getId(), labelGroup == null ? null : labelGroup.getProjectid(), labelGroup == null ? null : labelGroup.getType(), json(index), ts(index.getCreateTime()), ts(index.getUpdateTime()));
    }

    private void saveProjectMember(SystemIndex index) {
        ProjectMember member = index.getProjectMember();
        jdbcTemplate.update("""
                        INSERT INTO oat_project_member (id, project_id, member_id, payload_json, create_time, update_time)
                        VALUES (?, ?, ?, ?::jsonb, ?, ?)
                        ON CONFLICT (id) DO UPDATE SET project_id=EXCLUDED.project_id, member_id=EXCLUDED.member_id, payload_json=EXCLUDED.payload_json, create_time=EXCLUDED.create_time, update_time=EXCLUDED.update_time
                        """, index.getId(), member == null ? null : member.getProjectId(), member == null ? null : member.getMemberId(), json(index), ts(index.getCreateTime()), ts(index.getUpdateTime()));
    }

    private void saveSystemLog(SystemIndex index) {
        SystemLog log = index.getSystemLog();
        jdbcTemplate.update("""
                        INSERT INTO oat_system_log (id, project_id, user_id, user_name, action, title, payload_json, create_time, update_time)
                        VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                        ON CONFLICT (id) DO UPDATE SET project_id=EXCLUDED.project_id, user_id=EXCLUDED.user_id, user_name=EXCLUDED.user_name, action=EXCLUDED.action, title=EXCLUDED.title, payload_json=EXCLUDED.payload_json, create_time=EXCLUDED.create_time, update_time=EXCLUDED.update_time
                        """, index.getId(), log == null ? null : log.getProjectId(), log == null ? null : log.getUserId(), log == null ? null : log.getUserName(), log == null ? null : log.getAction(), log == null ? null : log.getTitle(), json(index), ts(index.getCreateTime()), ts(index.getUpdateTime()));
    }

    private List<SystemIndex> queryPage(String type, String whereClause, Pageable pageable, Object... args) {
        Table table = table(type);
        String sql = "SELECT '" + type + "' type, id, payload_json, create_time, update_time FROM " + table.name + " WHERE " + whereClause + " ORDER BY update_time DESC";
        List<Object> params = new ArrayList<>(Arrays.asList(args));
        if (pageable != null && pageable.isPaged()) {
            sql += " LIMIT ? OFFSET ?";
            params.add(pageable.getPageSize());
            params.add(pageable.getOffset());
        }
        return query(sql, params.toArray());
    }

    private List<SystemIndex> queryUnion(String whereClause, String orderBy, Object... args) {
        String sql = String.join(" UNION ALL ",
                "SELECT 'user' type, id, payload_json, create_time, update_time FROM oat_user WHERE " + whereClause,
                "SELECT 'project' type, id, payload_json, create_time, update_time FROM oat_project WHERE " + whereClause,
                "SELECT 'app' type, id, payload_json, create_time, update_time FROM oat_app WHERE " + whereClause,
                "SELECT 'labelGroup' type, id, payload_json, create_time, update_time FROM oat_label_group WHERE " + whereClause,
                "SELECT 'projectMember' type, id, payload_json, create_time, update_time FROM oat_project_member WHERE " + whereClause,
                "SELECT 'systemLog' type, id, payload_json, create_time, update_time FROM oat_system_log WHERE " + whereClause);
        if (orderBy != null) sql = "SELECT * FROM (" + sql + ") t ORDER BY " + orderBy;
        Object[] params = repeatArgs(args, 6);
        return query(sql, params);
    }

    private Object[] repeatArgs(Object[] args, int times) {
        List<Object> params = new ArrayList<>();
        for (int i = 0; i < times; i++) params.addAll(Arrays.asList(args));
        return params.toArray();
    }

    private List<SystemIndex> query(String sql, Object... args) {
        return jdbcTemplate.query(sql, rowMapper, args);
    }

    private void normalize(SystemIndex index) {
        if (!StringUtils.hasText(index.getId())) index.setId(UUID.randomUUID().toString());
        if (!StringUtils.hasText(index.getType())) throw new IllegalArgumentException("system index type must not be empty");
        Date now = new Date();
        if (index.getCreateTime() == null) index.setCreateTime(now);
        if (index.getUpdateTime() == null) index.setUpdateTime(now);
    }

    private SystemIndex mapRow(ResultSet rs, int rowNum) throws SQLException {
        SystemIndex index = UtilJson.convertValue(rs.getString("payload_json"), SystemIndex.class);
        if (index == null) index = new SystemIndex();
        index.setId(rs.getString("id"));
        index.setType(rs.getString("type"));
        index.setCreateTime(toDate(rs.getTimestamp("create_time")));
        index.setUpdateTime(toDate(rs.getTimestamp("update_time")));
        return index;
    }

    private String json(Object value) { return UtilJson.writeValueAsString(value); }
    private Timestamp ts(Date date) { return date == null ? null : new Timestamp(date.getTime()); }
    private Date toDate(Timestamp timestamp) { return timestamp == null ? null : new Date(timestamp.getTime()); }

    private Table table(String type) {
        return switch (type) {
            case "user" -> new Table("oat_user");
            case "project" -> new Table("oat_project");
            case "app" -> new Table("oat_app");
            case "labelGroup" -> new Table("oat_label_group");
            case "projectMember" -> new Table("oat_project_member");
            case "systemLog" -> new Table("oat_system_log");
            default -> throw new IllegalArgumentException("unsupported system index type: " + type);
        };
    }

    private record Table(String name) {}
}
