ALTER TABLE oat_verification_asset
    ADD COLUMN IF NOT EXISTS ai_generated BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_oat_verification_asset_ai_generated
    ON oat_verification_asset (project_id, ai_generated, create_time DESC);
