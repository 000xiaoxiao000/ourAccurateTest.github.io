package com.oAT.web.esDao;

import com.oAT.web.common.UtilJson;
import com.oAT.web.esDao.entity.StaticSourceClassInfo;
import com.oAT.web.esDao.entity.StaticSourceInfo;
import com.oAT.web.esDao.entity.StaticSourceMethodInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class StaticInfoRepository {
    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<StaticSourceInfo> rowMapper = this::mapRow;

    @Value("${oat.storage.large-payload.path:${user.home}/oAT/codeData/large-payload/}")
    private String largePayloadPath;

    @Value("${oat.storage.static-source.inline-threshold-bytes:8192}")
    private long inlineThresholdBytes;

    public StaticInfoRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<StaticSourceInfo> findByAppIdAndClassInfo_ClassName(String appId, String className) {
        return jdbcTemplate.query("""
                        SELECT * FROM oat_static_source_class
                        WHERE app_id = ? AND class_name = ?
                        ORDER BY update_time DESC
                        """,
                rowMapper, appId, className);
    }

    public List<StaticSourceInfo> findByAppId(String appId) {
        return jdbcTemplate.query("""
                        SELECT * FROM oat_static_source_class
                        WHERE app_id = ?
                        ORDER BY class_name ASC
                        """,
                rowMapper, appId);
    }

    @Transactional
    public StaticSourceInfo save(StaticSourceInfo info) {
        normalize(info);
        StaticSourceClassInfo classInfo = info.getClassInfo();
        SourcePayload sourcePayload = storeSourceCode(info.getAppId(), classInfo.getClassName(), classInfo.getSourceCode());
        jdbcTemplate.update("""
                        INSERT INTO oat_static_source_class (
                            id, app_id, type, class_id, class_name, method_maps_json,
                            source_code, source_code_path, source_code_hash, source_code_size,
                            payload_json, create_time, update_time
                        ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?::jsonb, ?, ?)
                        ON CONFLICT (app_id, class_name) DO UPDATE SET
                            id = EXCLUDED.id,
                            type = EXCLUDED.type,
                            class_id = EXCLUDED.class_id,
                            method_maps_json = EXCLUDED.method_maps_json,
                            source_code = EXCLUDED.source_code,
                            source_code_path = EXCLUDED.source_code_path,
                            source_code_hash = EXCLUDED.source_code_hash,
                            source_code_size = EXCLUDED.source_code_size,
                            payload_json = EXCLUDED.payload_json,
                            update_time = EXCLUDED.update_time
                        """,
                info.getId(),
                info.getAppId(),
                info.getType(),
                classInfo.getClassId(),
                classInfo.getClassName(),
                UtilJson.writeValueAsString(classInfo.getMethodMaps()),
                sourcePayload.inlineContent(),
                sourcePayload.path(),
                sourcePayload.hash(),
                sourcePayload.size(),
                UtilJson.writeValueAsString(info),
                toTimestamp(info.getCreateTime()),
                toTimestamp(info.getUpdateTime()));
        return info;
    }

    private void normalize(StaticSourceInfo info) {
        if (!StringUtils.hasText(info.getId())) {
            info.setId(UUID.randomUUID().toString());
        }
        if (!StringUtils.hasText(info.getType())) {
            info.setType("classInfo");
        }
        if (!StringUtils.hasText(info.getAppId())) {
            throw new IllegalArgumentException("appId must not be empty");
        }
        if (info.getClassInfo() == null || !StringUtils.hasText(info.getClassInfo().getClassName())) {
            throw new IllegalArgumentException("className must not be empty");
        }
        Date now = new Date();
        if (info.getCreateTime() == null) {
            info.setCreateTime(now);
        }
        if (info.getUpdateTime() == null) {
            info.setUpdateTime(now);
        }
    }

    private StaticSourceInfo mapRow(ResultSet rs, int rowNum) throws SQLException {
        StaticSourceInfo info = UtilJson.convertValue(rs.getString("payload_json"), StaticSourceInfo.class);
        if (info == null) {
            info = new StaticSourceInfo();
        }
        info.setId(rs.getString("id"));
        info.setAppId(rs.getString("app_id"));
        info.setType(rs.getString("type"));
        info.setCreateTime(toDate(rs.getTimestamp("create_time")));
        info.setUpdateTime(toDate(rs.getTimestamp("update_time")));
        if (info.getClassInfo() == null) {
            StaticSourceClassInfo classInfo = new StaticSourceClassInfo();
            classInfo.setClassId(rs.getString("class_id"));
            classInfo.setClassName(rs.getString("class_name"));
            String methodMapsJson = rs.getString("method_maps_json");
            if (methodMapsJson != null && !methodMapsJson.isEmpty()) {
                try {
                    Map<String, StaticSourceMethodInfo> methodMaps = UtilJson.getObjectMapper()
                            .readValue(methodMapsJson,
                                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, StaticSourceMethodInfo>>() {});
                    classInfo.setMethodMaps(methodMaps);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to deserialize methodMaps", e);
                }
            }
            classInfo.setSourceCode(loadSourceCode(rs.getString("source_code"), rs.getString("source_code_path")));
            info.setClassInfo(classInfo);
        } else if (info.getClassInfo().getSourceCode() == null) {
            info.getClassInfo().setSourceCode(loadSourceCode(rs.getString("source_code"), rs.getString("source_code_path")));
        }
        return info;
    }

    private SourcePayload storeSourceCode(String appId, String className, String sourceCode) {
        if (sourceCode == null) {
            return new SourcePayload(null, null, null, null);
        }
        byte[] bytes = sourceCode.getBytes(StandardCharsets.UTF_8);
        String hash = sha256(bytes);
        if (bytes.length <= inlineThresholdBytes) {
            return new SourcePayload(sourceCode, null, hash, (long) bytes.length);
        }
        try {
            String safeClassName = className == null ? "unknown" : className.replaceAll("[^a-zA-Z0-9._-]", "_");
            Path relativePath = Path.of("static-source", appId == null ? "unknown-app" : appId, safeClassName + "-" + hash + ".java");
            Path root = Path.of(largePayloadPath);
            Path file = root.resolve(relativePath);
            Files.createDirectories(file.getParent());
            Files.writeString(file, sourceCode, StandardCharsets.UTF_8);
            return new SourcePayload(null, relativePath.toString(), hash, (long) bytes.length);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to store static source code", e);
        }
    }

    private String loadSourceCode(String inlineContent, String sourcePath) {
        if (StringUtils.hasText(inlineContent)) {
            return inlineContent;
        }
        if (!StringUtils.hasText(sourcePath)) {
            return null;
        }
        try {
            return Files.readString(Path.of(largePayloadPath).resolve(sourcePath), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record SourcePayload(String inlineContent, String path, String hash, Long size) {}

    private Timestamp toTimestamp(Date date) {
        return date == null ? null : new Timestamp(date.getTime());
    }

    private Date toDate(Timestamp timestamp) {
        return timestamp == null ? null : new Date(timestamp.getTime());
    }
}
