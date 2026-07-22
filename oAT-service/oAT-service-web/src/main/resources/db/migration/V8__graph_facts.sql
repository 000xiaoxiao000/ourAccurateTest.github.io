CREATE TABLE IF NOT EXISTS oat_graph_snapshot (
  id VARCHAR(64) PRIMARY KEY,
  project_id VARCHAR(64) NOT NULL,
  baseline_id VARCHAR(64) NOT NULL,
  repository_url VARCHAR(1024),
  source_commit VARCHAR(128),
  snapshot_kind VARCHAR(32) NOT NULL,
  analyzer_version VARCHAR(64) NOT NULL,
  input_hash VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  invalidated_at TIMESTAMPTZ,
  attributes_json JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_graph_snapshot_identity
  ON oat_graph_snapshot (baseline_id, snapshot_kind, analyzer_version, input_hash);
CREATE INDEX IF NOT EXISTS idx_graph_snapshot_project_commit
  ON oat_graph_snapshot (project_id, source_commit, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_graph_snapshot_active
  ON oat_graph_snapshot (baseline_id, snapshot_kind) WHERE invalidated_at IS NULL;

CREATE TABLE IF NOT EXISTS oat_graph_node (
  id VARCHAR(128) PRIMARY KEY,
  snapshot_id VARCHAR(64) NOT NULL,
  baseline_id VARCHAR(64) NOT NULL,
  project_id VARCHAR(64) NOT NULL,
  node_kind VARCHAR(32) NOT NULL,
  stable_symbol_id VARCHAR(1024),
  logical_symbol_id VARCHAR(1024),
  locator VARCHAR(1024),
  display_name VARCHAR(512) NOT NULL,
  content_hash VARCHAR(64),
  attributes_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  valid_from TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  invalidated_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_graph_node_snapshot_symbol
  ON oat_graph_node (snapshot_id, stable_symbol_id) WHERE stable_symbol_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_graph_node_baseline_kind_active
  ON oat_graph_node (baseline_id, node_kind) WHERE invalidated_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_graph_node_logical_symbol_active
  ON oat_graph_node (project_id, logical_symbol_id) WHERE invalidated_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_graph_node_attributes_gin
  ON oat_graph_node USING GIN (attributes_json);

CREATE TABLE IF NOT EXISTS oat_graph_edge (
  id VARCHAR(128) PRIMARY KEY,
  snapshot_id VARCHAR(64) NOT NULL,
  baseline_id VARCHAR(64) NOT NULL,
  project_id VARCHAR(64) NOT NULL,
  source_node_id VARCHAR(128) NOT NULL,
  target_node_id VARCHAR(128) NOT NULL,
  edge_type VARCHAR(48) NOT NULL,
  evidence_kind VARCHAR(48) NOT NULL,
  evidence_level VARCHAR(8) NOT NULL,
  confidence NUMERIC(5,4) NOT NULL DEFAULT 1.0000,
  execution_id VARCHAR(64),
  evidence_locator VARCHAR(1024),
  attributes_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  valid_from TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  invalidated_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_graph_edge_identity
  ON oat_graph_edge (snapshot_id, source_node_id, target_node_id, edge_type, evidence_kind, COALESCE(execution_id, ''));
CREATE INDEX IF NOT EXISTS idx_graph_edge_source_active
  ON oat_graph_edge (baseline_id, source_node_id, edge_type) WHERE invalidated_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_graph_edge_target_active
  ON oat_graph_edge (baseline_id, target_node_id, edge_type) WHERE invalidated_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_graph_edge_execution_active
  ON oat_graph_edge (execution_id, edge_type) WHERE invalidated_at IS NULL AND execution_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_graph_edge_attributes_gin
  ON oat_graph_edge USING GIN (attributes_json);

CREATE TABLE IF NOT EXISTS oat_runtime_execution (
  id VARCHAR(64) PRIMARY KEY,
  project_id VARCHAR(64) NOT NULL,
  baseline_id VARCHAR(64) NOT NULL,
  source_commit VARCHAR(128),
  external_execution_id VARCHAR(256),
  environment VARCHAR(128),
  collector_version VARCHAR(64),
  trace_hash VARCHAR(64),
  coverage_hash VARCHAR(64),
  started_at TIMESTAMPTZ,
  finished_at TIMESTAMPTZ,
  captured_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  attributes_json JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX IF NOT EXISTS idx_runtime_execution_baseline_captured
  ON oat_runtime_execution (baseline_id, captured_at DESC);
CREATE INDEX IF NOT EXISTS idx_runtime_execution_commit
  ON oat_runtime_execution (project_id, source_commit, captured_at DESC);

CREATE TABLE IF NOT EXISTS oat_graph_aggregate (
  id VARCHAR(128) PRIMARY KEY,
  baseline_id VARCHAR(64) NOT NULL,
  project_id VARCHAR(64) NOT NULL,
  aggregate_kind VARCHAR(48) NOT NULL,
  subject_id VARCHAR(128) NOT NULL,
  source_hash VARCHAR(64) NOT NULL,
  payload_json JSONB NOT NULL,
  calculated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  invalidated_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_graph_aggregate_current
  ON oat_graph_aggregate (baseline_id, aggregate_kind, subject_id, source_hash);
CREATE INDEX IF NOT EXISTS idx_graph_aggregate_active
  ON oat_graph_aggregate (baseline_id, aggregate_kind) WHERE invalidated_at IS NULL;
