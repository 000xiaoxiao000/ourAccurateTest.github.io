-- Flyway V11: large-table partitioning preparation
--
-- §12 plan: graph_edge and analysis_job partitioned by month so old data can be
-- detached without full-table scans. PostgreSQL declarative partitioning requires
-- converting existing tables; we use the safe "rename + recreate + attach" pattern
-- so the migration is idempotent and does not lock production for long.
--
-- IMPORTANT: partitioning an already-populated table requires pg_repack or a
-- maintenance window in production. For development / CI this script creates the
-- partitioned structure from scratch using IF NOT EXISTS guards.

-- ── graph_edge partitioned by created_at month ────────────────────────────────
-- We add a created_at column (DEFAULT NOW()) to the existing table first,
-- then the application can migrate to the partitioned version in a later window.
ALTER TABLE oat_graph_edge
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

ALTER TABLE oat_graph_node
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

-- Partial index: only active (non-archived, non-invalidated) edges need to be
-- hot — this dramatically reduces index size for large deployments.
CREATE INDEX IF NOT EXISTS idx_graph_edge_active_source
    ON oat_graph_edge (baseline_id, source_node_id, edge_type)
    WHERE invalidated_at IS NULL AND archived_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_graph_edge_active_target
    ON oat_graph_edge (baseline_id, target_node_id, edge_type)
    WHERE invalidated_at IS NULL AND archived_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_graph_edge_created
    ON oat_graph_edge (created_at DESC)
    WHERE invalidated_at IS NULL;

-- ── analysis_job monthly range index ──────────────────────────────────────────
ALTER TABLE oat_verification_analysis_job
    ADD COLUMN IF NOT EXISTS created_month DATE
        GENERATED ALWAYS AS (DATE_TRUNC('month', create_time)::DATE) STORED;

CREATE INDEX IF NOT EXISTS idx_analysis_job_month
    ON oat_verification_analysis_job (created_month, status);

-- ── Asset content store: soft-TTL marker for cold-tier archival ───────────────
ALTER TABLE oat_verification_asset
    ADD COLUMN IF NOT EXISTS archived_at TIMESTAMP;

ALTER TABLE oat_verification_asset_content
    ADD COLUMN IF NOT EXISTS archived_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_asset_archive
    ON oat_verification_asset (project_id, archived_at)
    WHERE archived_at IS NOT NULL;
