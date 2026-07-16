package com.oAT.web.esDao;

import com.oAT.web.esDao.entity.ApiEndpointIndex;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Repository
public class ApiEndpointRepository {
    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<ApiEndpointIndex> rowMapper = this::mapRow;

    public ApiEndpointRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ApiEndpointIndex> findByAppIdOrderByEndpointTypeAscUrlAsc(String appId) {
        return jdbcTemplate.query("""
                        SELECT * FROM oat_api_endpoint
                        WHERE app_id = ?
                        ORDER BY endpoint_type ASC, url ASC
                        """,
                rowMapper, appId);
    }

    @Transactional
    public void deleteByAppId(String appId) {
        jdbcTemplate.update("DELETE FROM oat_api_endpoint WHERE app_id = ?", appId);
    }

    @Transactional
    public List<ApiEndpointIndex> saveAll(Iterable<ApiEndpointIndex> endpoints) {
        List<ApiEndpointIndex> saved = new ArrayList<>();
        for (ApiEndpointIndex endpoint : endpoints) {
            saved.add(save(endpoint));
        }
        return saved;
    }

    @Transactional
    public ApiEndpointIndex save(ApiEndpointIndex endpoint) {
        normalize(endpoint);
        jdbcTemplate.update("""
                        INSERT INTO oat_api_endpoint (
                            id, app_id, source_type, source_name, source_names, source_type_names,
                            endpoint_type, url, http_method, class_name, class_names, method_name,
                            method_names, method_desc, method_descs, covered,
                            hit_count, merged_source_count, create_time, update_time
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (id) DO UPDATE SET
                            app_id = EXCLUDED.app_id,
                            source_type = EXCLUDED.source_type,
                            source_name = EXCLUDED.source_name,
                            source_names = EXCLUDED.source_names,
                            source_type_names = EXCLUDED.source_type_names,
                            endpoint_type = EXCLUDED.endpoint_type,
                            url = EXCLUDED.url,
                            http_method = EXCLUDED.http_method,
                            class_name = EXCLUDED.class_name,
                            class_names = EXCLUDED.class_names,
                            method_name = EXCLUDED.method_name,
                            method_names = EXCLUDED.method_names,
                            method_desc = EXCLUDED.method_desc,
                            method_descs = EXCLUDED.method_descs,
                            covered = EXCLUDED.covered,
                            hit_count = EXCLUDED.hit_count,
                            merged_source_count = EXCLUDED.merged_source_count,
                            update_time = EXCLUDED.update_time
                        """,
                endpoint.getId(),
                endpoint.getAppId(),
                endpoint.getSourceType(),
                endpoint.getSourceName(),
                endpoint.getSourceNames(),
                endpoint.getSourceTypeNames(),
                endpoint.getEndpointType(),
                endpoint.getUrl(),
                endpoint.getHttpMethod(),
                endpoint.getClassName(),
                endpoint.getClassNames(),
                endpoint.getMethodName(),
                endpoint.getMethodNames(),
                endpoint.getMethodDesc(),
                endpoint.getMethodDescs(),
                endpoint.getCovered(),
                endpoint.getHitCount(),
                endpoint.getMergedSourceCount(),
                toTimestamp(endpoint.getCreateTime()),
                toTimestamp(endpoint.getUpdateTime()));
        return endpoint;
    }

    private void normalize(ApiEndpointIndex endpoint) {
        if (!StringUtils.hasText(endpoint.getId())) {
            endpoint.setId(UUID.randomUUID().toString());
        }
        if (!StringUtils.hasText(endpoint.getAppId())) {
            throw new IllegalArgumentException("appId must not be empty");
        }
        Date now = new Date();
        if (endpoint.getCreateTime() == null) {
            endpoint.setCreateTime(now);
        }
        if (endpoint.getUpdateTime() == null) {
            endpoint.setUpdateTime(now);
        }
    }

    private ApiEndpointIndex mapRow(ResultSet rs, int rowNum) throws SQLException {
        ApiEndpointIndex endpoint = new ApiEndpointIndex();
        endpoint.setId(rs.getString("id"));
        endpoint.setAppId(rs.getString("app_id"));
        endpoint.setSourceType(rs.getString("source_type"));
        endpoint.setSourceName(rs.getString("source_name"));
        endpoint.setSourceNames(rs.getString("source_names"));
        endpoint.setSourceTypeNames(rs.getString("source_type_names"));
        endpoint.setEndpointType(rs.getString("endpoint_type"));
        endpoint.setUrl(rs.getString("url"));
        endpoint.setHttpMethod(rs.getString("http_method"));
        endpoint.setClassName(rs.getString("class_name"));
        endpoint.setClassNames(rs.getString("class_names"));
        endpoint.setMethodName(rs.getString("method_name"));
        endpoint.setMethodNames(rs.getString("method_names"));
        endpoint.setMethodDesc(rs.getString("method_desc"));
        endpoint.setMethodDescs(rs.getString("method_descs"));
        endpoint.setCovered(getBoolean(rs, "covered"));
        endpoint.setHitCount(getInteger(rs, "hit_count"));
        endpoint.setMergedSourceCount(getInteger(rs, "merged_source_count"));
        endpoint.setCreateTime(toDate(rs.getTimestamp("create_time")));
        endpoint.setUpdateTime(toDate(rs.getTimestamp("update_time")));
        return endpoint;
    }

    private Boolean getBoolean(ResultSet rs, String columnName) throws SQLException {
        boolean value = rs.getBoolean(columnName);
        return rs.wasNull() ? null : value;
    }

    private Integer getInteger(ResultSet rs, String columnName) throws SQLException {
        int value = rs.getInt(columnName);
        return rs.wasNull() ? null : value;
    }

    private Timestamp toTimestamp(Date date) {
        return date == null ? null : new Timestamp(date.getTime());
    }

    private Date toDate(Timestamp timestamp) {
        return timestamp == null ? null : new Date(timestamp.getTime());
    }
}
