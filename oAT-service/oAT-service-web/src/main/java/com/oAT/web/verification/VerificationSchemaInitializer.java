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
            "db/postgresql/phase5_normalized_core.sql",
            "db/postgresql/phase2_api_endpoint.sql",
            "db/postgresql/phase6_ai_verification.sql",
            "db/postgresql/phase7_traceability_gate.sql",
            "db/postgresql/phase8_graph_facts.sql"
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
            ensureColumn("oat_app", "language", "ALTER TABLE oat_app ADD COLUMN IF NOT EXISTS language VARCHAR(32)");
            ensureColumn("oat_app", "language_config_json", "ALTER TABLE oat_app ADD COLUMN IF NOT EXISTS language_config_json JSONB");
            ensureColumn("oat_verification_baseline", "execution_asset_id", "ALTER TABLE oat_verification_baseline ADD COLUMN IF NOT EXISTS execution_asset_id VARCHAR(64)");
            ensureColumn("oat_verification_baseline", "coverage_asset_id", "ALTER TABLE oat_verification_baseline ADD COLUMN IF NOT EXISTS coverage_asset_id VARCHAR(64)");
            relaxColumnNotNull("ALTER TABLE oat_verification_baseline ALTER COLUMN requirement_asset_id DROP NOT NULL");
            relaxColumnNotNull("ALTER TABLE oat_verification_baseline ALTER COLUMN testcase_asset_id DROP NOT NULL");
            ensureColumn("oat_verification_asset", "storage_type", "ALTER TABLE oat_verification_asset ADD COLUMN IF NOT EXISTS storage_type VARCHAR(32) NOT NULL DEFAULT 'DATABASE'");
            ensureColumn("oat_verification_asset", "storage_key", "ALTER TABLE oat_verification_asset ADD COLUMN IF NOT EXISTS storage_key VARCHAR(512)");
            ensureColumn("oat_verification_asset", "content_size", "ALTER TABLE oat_verification_asset ADD COLUMN IF NOT EXISTS content_size BIGINT NOT NULL DEFAULT 0");
            ensureColumn("oat_verification_asset", "content_preview", "ALTER TABLE oat_verification_asset ADD COLUMN IF NOT EXISTS content_preview TEXT");
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
                .filter(statement -> statement.toUpperCase().startsWith("CREATE TABLE")
                        || statement.toUpperCase().startsWith("CREATE INDEX")
                        || statement.toUpperCase().startsWith("CREATE UNIQUE INDEX"))
                .forEach(jdbcTemplate::execute);
    }

    private void ensureColumn(String tableName, String columnName, String alterSql) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE table_catalog = current_database() AND table_schema = current_schema()
                  AND table_name = ? AND column_name = ?
                """, Integer.class, tableName, columnName);
        if (count == null || count == 0) {
            jdbcTemplate.execute(alterSql);
        }
    }

    private void relaxColumnNotNull(String alterSql) {
        try {
            jdbcTemplate.execute(alterSql);
        } catch (Exception e) {
            logger.debug("Schema nullability adjustment skipped: {}", e.getMessage());
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
