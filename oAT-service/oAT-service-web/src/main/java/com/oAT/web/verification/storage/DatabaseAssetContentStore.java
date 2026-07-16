package com.oAT.web.verification.storage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class DatabaseAssetContentStore implements AssetContentStore {
    private static final int PREVIEW_CHARS = 600;
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
                    project_id = EXCLUDED.project_id,
                    asset_id = EXCLUDED.asset_id,
                    content_hash = EXCLUDED.content_hash,
                    content_text = EXCLUDED.content_text,
                    content_size = EXCLUDED.content_size,
                    content_preview = EXCLUDED.content_preview,
                    update_time = CURRENT_TIMESTAMP
                """, key, command.projectId(), command.assetId(), command.contentHash(), command.content(),
                size(command.content()), preview(command.content()));
        return new StoredContent(storageType(), key, size(command.content()), preview(command.content()));
    }

    @Override
    public String load(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) return "";
        return jdbc.query("SELECT content_text FROM oat_verification_asset_content WHERE storage_key = ?",
                (rs, row) -> rs.getString("content_text"), storageKey).stream().findFirst().orElse("");
    }

    @Override
    public void delete(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) return;
        jdbc.update("DELETE FROM oat_verification_asset_content WHERE storage_key = ?", storageKey);
    }

    private long size(String content) {
        return content == null ? 0 : content.getBytes(StandardCharsets.UTF_8).length;
    }

    private String preview(String content) {
        if (content == null) return "";
        return content.length() <= PREVIEW_CHARS ? content : content.substring(0, PREVIEW_CHARS);
    }
}
