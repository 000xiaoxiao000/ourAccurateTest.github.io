package com.oAT.web.verification.storage;

import com.oAT.web.logging.LogFields;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * DATABASE storage tier for asset content.
 *
 * Cold-tier archival (plan §12):
 *   - Assets older than WARM_TIER_DAYS that are not referenced by any active baseline
 *     are soft-archived: archived_at is set but content_text is NOT deleted yet.
 *   - Assets older than COLD_TIER_DAYS are hard-archived: content_text is cleared and
 *     only content_preview + content_hash + metadata are kept, so audit trails remain
 *     intact without keeping the full text in the primary DB.
 *   - The scheduler calls archiveExpiredContent() on a configurable schedule.
 */
@Component
public class DatabaseAssetContentStore implements AssetContentStore {
    private static final Logger log = LoggerFactory.getLogger(DatabaseAssetContentStore.class);
    private static final int PREVIEW_CHARS = 600;

    /** Days after which unreferenced asset content enters the warm-archive tier (soft mark only). */
    public static final int WARM_TIER_DAYS = 90;
    /** Days after which warm-archived content is hard-archived (content_text cleared). */
    public static final int COLD_TIER_DAYS = 365;

    private final JdbcTemplate jdbc;

    public DatabaseAssetContentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String storageType() {
        return "DATABASE";
    }

    @Override
    public StoredContent store(StoreCommand command) {
        String key = command.assetId();
        jdbc.update("""
                INSERT INTO oat_verification_asset_content
                (storage_key, project_id, asset_id, content_hash, content_text, content_size, content_preview)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (storage_key) DO UPDATE SET
                    project_id    = EXCLUDED.project_id,
                    asset_id      = EXCLUDED.asset_id,
                    content_hash  = EXCLUDED.content_hash,
                    content_text  = EXCLUDED.content_text,
                    content_size  = EXCLUDED.content_size,
                    content_preview = EXCLUDED.content_preview,
                    archived_at   = NULL,
                    update_time   = CURRENT_TIMESTAMP
                """, key, command.projectId(), command.assetId(), command.contentHash(), command.content(),
                size(command.content()), preview(command.content()));
        return new StoredContent(storageType(), key, size(command.content()), preview(command.content()));
    }

    @Override
    public String load(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) return "";
        return jdbc.query("""
                SELECT content_text FROM oat_verification_asset_content
                WHERE storage_key = ? AND archived_at IS NULL
                """, (rs, row) -> rs.getString("content_text"), storageKey)
                .stream().findFirst().orElse("");
    }

    /** Load content even if it has been soft-archived (for explicit retrieval flows). */
    public String loadIncludingArchived(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) return "";
        return jdbc.query(
                "SELECT content_text FROM oat_verification_asset_content WHERE storage_key = ?",
                (rs, row) -> rs.getString("content_text"), storageKey)
                .stream().findFirst().orElse("");
    }

    @Override
    public void delete(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) return;
        jdbc.update("DELETE FROM oat_verification_asset_content WHERE storage_key = ?", storageKey);
    }

    /**
     * Soft-archive: mark content older than warmTierDays that is NOT referenced by any active
     * baseline. Sets archived_at but keeps content_text intact (warm tier: still readable).
     *
     * @return number of rows soft-archived
     */
    public int softArchiveExpiredContent(int warmTierDays) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(warmTierDays);
        int rows = jdbc.update("""
                UPDATE oat_verification_asset_content
                SET archived_at = CURRENT_TIMESTAMP
                WHERE archived_at IS NULL
                  AND update_time < ?
                  AND asset_id NOT IN (
                      SELECT COALESCE(requirement_asset_id, '')
                        FROM oat_verification_baseline WHERE status NOT IN ('STALE','FAILED')
                      UNION
                      SELECT COALESCE(testcase_asset_id, '')
                        FROM oat_verification_baseline WHERE status NOT IN ('STALE','FAILED')
                      UNION
                      SELECT COALESCE(source_asset_id, '')
                        FROM oat_verification_baseline WHERE status NOT IN ('STALE','FAILED')
                      UNION
                      SELECT COALESCE(execution_asset_id, '')
                        FROM oat_verification_baseline WHERE status NOT IN ('STALE','FAILED')
                      UNION
                      SELECT COALESCE(coverage_asset_id, '')
                        FROM oat_verification_baseline WHERE status NOT IN ('STALE','FAILED')
                  )
                """, Timestamp.valueOf(cutoff));
        if (rows > 0) {
            log.info("event=asset_content.soft_archive.completed {}", LogFields.of(LogFields.map(
                    "archived_rows", rows,
                    "warm_tier_days", warmTierDays)));
        }
        return rows;
    }

    /**
     * Hard-archive: clear content_text for rows already soft-archived longer than coldTierDays ago.
     * Preview and hash are preserved so audit trails remain intact.
     *
     * @return number of rows hard-archived
     */
    public int hardArchiveExpiredContent(int coldTierDays) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(coldTierDays);
        int rows = jdbc.update("""
                UPDATE oat_verification_asset_content
                SET content_text = NULL
                WHERE archived_at IS NOT NULL
                  AND archived_at < ?
                  AND content_text IS NOT NULL
                """, Timestamp.valueOf(cutoff));
        if (rows > 0) {
            log.info("event=asset_content.hard_archive.completed {}", LogFields.of(LogFields.map(
                    "archived_rows", rows,
                    "cold_tier_days", coldTierDays)));
        }
        return rows;
    }

    /** Run both archival passes with default thresholds. Returns [softCount, hardCount]. */
    public int[] archiveExpiredContent() {
        return new int[]{
                softArchiveExpiredContent(WARM_TIER_DAYS),
                hardArchiveExpiredContent(COLD_TIER_DAYS)
        };
    }

    private long size(String content) {
        return content == null ? 0 : content.getBytes(StandardCharsets.UTF_8).length;
    }

    private String preview(String content) {
        if (content == null) return "";
        return content.length() <= PREVIEW_CHARS ? content : content.substring(0, PREVIEW_CHARS);
    }
}
