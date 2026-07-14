package com.oAT.web.verification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

@Component
public class VerificationSchemaInitializer {
    private static final Logger logger = LoggerFactory.getLogger(VerificationSchemaInitializer.class);
    private static final List<String> SCHEMA_RESOURCES = List.of(
            "db/mysql/phase5_normalized_core.sql",
            "db/mysql/phase2_api_endpoint.sql",
            "db/mysql/phase6_ai_verification.sql"
    );
    private final JdbcTemplate jdbcTemplate;

    public VerificationSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        try {
            for (String location : SCHEMA_RESOURCES) {
                executeCreateTableStatements(location);
            }
            ensureColumn("oat_app", "language", "ALTER TABLE oat_app ADD COLUMN language VARCHAR(32) NULL AFTER src_name");
            ensureColumn("oat_app", "language_config_json", "ALTER TABLE oat_app ADD COLUMN language_config_json JSON NULL AFTER language");
            ensureColumn("oat_verification_baseline", "execution_asset_id", "ALTER TABLE oat_verification_baseline ADD COLUMN execution_asset_id VARCHAR(64) NULL AFTER source_asset_id");
            ensureColumn("oat_verification_baseline", "coverage_asset_id", "ALTER TABLE oat_verification_baseline ADD COLUMN coverage_asset_id VARCHAR(64) NULL AFTER execution_asset_id");
            ensureColumn("oat_verification_asset", "storage_type", "ALTER TABLE oat_verification_asset ADD COLUMN storage_type VARCHAR(32) NOT NULL DEFAULT 'MYSQL' AFTER content_text");
            ensureColumn("oat_verification_asset", "storage_key", "ALTER TABLE oat_verification_asset ADD COLUMN storage_key VARCHAR(512) NULL AFTER storage_type");
            ensureColumn("oat_verification_asset", "content_size", "ALTER TABLE oat_verification_asset ADD COLUMN content_size BIGINT NOT NULL DEFAULT 0 AFTER storage_key");
            ensureColumn("oat_verification_asset", "content_preview", "ALTER TABLE oat_verification_asset ADD COLUMN content_preview TEXT NULL AFTER content_size");
            logger.info("Database schema initialized");
        } catch (Exception e) {
            logger.warn("Database schema initialization skipped or failed: {}", e.getMessage());
        }
    }

    private void executeCreateTableStatements(String location) throws Exception {
        ClassPathResource resource = new ClassPathResource(location);
        if (!resource.exists()) return;
        String sql = stripLineComments(new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        Arrays.stream(sql.split(";"))
                .map(String::trim)
                .filter(statement -> statement.toUpperCase().startsWith("CREATE TABLE"))
                .forEach(jdbcTemplate::execute);
    }

    private void ensureColumn(String tableName, String columnName, String alterSql) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?
                """, Integer.class, tableName, columnName);
        if (count == null || count == 0) {
            jdbcTemplate.execute(alterSql);
        }
    }

    private String stripLineComments(String sql) {
        StringBuilder builder = new StringBuilder();
        for (String line : sql.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("--")) builder.append(line).append('\n');
        }
        return builder.toString();
    }
}
