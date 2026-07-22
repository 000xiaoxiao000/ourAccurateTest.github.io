-- Flyway V10: Baseline graph snapshot version fields + TestExecution audit table
--
-- §9 plan: oat_verification_baseline needs graph snapshot version columns so every
-- baseline can answer "which static/runtime graph version produced this conclusion".
-- §3 plan: oat_runtime_test_execution provides D2 audit rows that bind a RuntimeCall
-- batch to a testcase key, environment, commit and collector version.

-- ── Baseline graph snapshot version columns ───────────────────────────────────
ALTER TABLE oat_verification_baseline
    ADD COLUMN IF NOT EXISTS static_graph_version    VARCHAR(64),
    ADD COLUMN IF NOT EXISTS runtime_graph_version   VARCHAR(64),
    ADD COLUMN IF NOT EXISTS cfg_hash                VARCHAR(64),
    ADD COLUMN IF NOT EXISTS dependency_hash         VARCHAR(64),
    ADD COLUMN IF NOT EXISTS coverage_report_hash    VARCHAR(64),
    ADD COLUMN IF NOT EXISTS execution_trace_hash    VARCHAR(64),
    ADD COLUMN IF NOT EXISTS symbol_hash             VARCHAR(64),
    ADD COLUMN IF NOT EXISTS invalidated_at          TIMESTAMP,
    ADD COLUMN IF NOT EXISTS superseded_by_baseline_id VARCHAR(64);

-- ── Runtime test execution audit (D2 level) ──────────────────────────────────
-- One row per batch import: each batch is one testcase run in one environment.
-- The execution_id here matches the executionId field in the RuntimeTraceBatch.
CREATE TABLE IF NOT EXISTS oat_runtime_test_execution (
    id               VARCHAR(64) PRIMARY KEY,
    project_id       VARCHAR(64) NOT NULL,
    baseline_id      VARCHAR(64) NOT NULL,
    source_commit    VARCHAR(256),
    external_execution_id VARCHAR(256) NOT NULL,
    testcase_key     VARCHAR(256),
    environment      VARCHAR(128) NOT NULL,
    collector_version VARCHAR(64) NOT NULL,
    trace_hash       VARCHAR(64),
    input_hash       VARCHAR(64),
    started_at       TIMESTAMPTZ,
    finished_at      TIMESTAMPTZ,
    captured_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    attributes_json  JSONB NOT NULL DEFAULT '{}'
);

CREATE INDEX IF NOT EXISTS idx_rte_baseline_time
    ON oat_runtime_test_execution (baseline_id, captured_at DESC);
CREATE INDEX IF NOT EXISTS idx_rte_commit
    ON oat_runtime_test_execution (baseline_id, source_commit);
CREATE UNIQUE INDEX IF NOT EXISTS uk_rte_baseline_external
    ON oat_runtime_test_execution (baseline_id, external_execution_id);

-- ── oat_graph_node / oat_graph_edge: archived_at for cold-tier marking ───────
ALTER TABLE oat_graph_node
    ADD COLUMN IF NOT EXISTS archived_at TIMESTAMP;
ALTER TABLE oat_graph_edge
    ADD COLUMN IF NOT EXISTS archived_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_graph_node_archive
    ON oat_graph_node (baseline_id, archived_at) WHERE archived_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_graph_edge_archive
    ON oat_graph_edge (baseline_id, archived_at) WHERE archived_at IS NOT NULL;
