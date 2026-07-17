CREATE TABLE IF NOT EXISTS oat_verification_asset (
  id VARCHAR(64) PRIMARY KEY,
  project_id VARCHAR(64) NOT NULL,
  asset_type VARCHAR(32) NOT NULL,
  source_type VARCHAR(32) NOT NULL,
  external_id VARCHAR(256),
  external_url VARCHAR(1024),
  source_version VARCHAR(256),
  file_name VARCHAR(512),
  content_hash VARCHAR(64) NOT NULL,
  content_text TEXT,
  storage_type VARCHAR(32) NOT NULL DEFAULT 'DATABASE',
  storage_key VARCHAR(512),
  content_size BIGINT NOT NULL DEFAULT 0,
  content_preview TEXT,
  metadata_json JSONB,
  freshness VARCHAR(32) NOT NULL DEFAULT 'SNAPSHOT',
  imported_by VARCHAR(64),
  captured_at TIMESTAMP NOT NULL,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_verification_asset_project_type ON oat_verification_asset (project_id, asset_type, create_time);
CREATE INDEX IF NOT EXISTS idx_verification_asset_hash ON oat_verification_asset (project_id, content_hash);

CREATE TABLE IF NOT EXISTS oat_verification_asset_content (
  storage_key VARCHAR(512) PRIMARY KEY,
  project_id VARCHAR(64) NOT NULL,
  asset_id VARCHAR(64) NOT NULL,
  content_hash VARCHAR(64) NOT NULL,
  content_text TEXT,
  content_size BIGINT NOT NULL DEFAULT 0,
  content_preview TEXT,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_verification_asset_content_project ON oat_verification_asset_content (project_id, asset_id);
CREATE INDEX IF NOT EXISTS idx_verification_asset_content_hash ON oat_verification_asset_content (project_id, content_hash);

CREATE TABLE IF NOT EXISTS oat_verification_baseline (
  id VARCHAR(64) PRIMARY KEY,
  project_id VARCHAR(64) NOT NULL,
  name VARCHAR(256) NOT NULL,
  requirement_asset_id VARCHAR(64),
  testcase_asset_id VARCHAR(64),
  source_asset_id VARCHAR(64),
  execution_asset_id VARCHAR(64),
  coverage_asset_id VARCHAR(64),
  source_app_id VARCHAR(64),
  repository_url VARCHAR(1024),
  source_branch VARCHAR(512),
  source_commit VARCHAR(128),
  analyzer_version VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  freshness VARCHAR(32) NOT NULL,
  created_by VARCHAR(64),
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_verification_baseline_project_time ON oat_verification_baseline (project_id, create_time);

CREATE TABLE IF NOT EXISTS oat_verification_ac (
  id VARCHAR(64) PRIMARY KEY,
  baseline_id VARCHAR(64) NOT NULL,
  requirement_key VARCHAR(256) NOT NULL,
  ac_key VARCHAR(256) NOT NULL,
  title VARCHAR(512),
  content_text TEXT NOT NULL,
  source_locator VARCHAR(512),
  priority VARCHAR(32),
  testable BOOLEAN NOT NULL DEFAULT TRUE,
  ambiguity BOOLEAN NOT NULL DEFAULT FALSE,
  confidence DECIMAL(5,4) NOT NULL DEFAULT 0,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_verification_ac_baseline ON oat_verification_ac (baseline_id, requirement_key);

CREATE TABLE IF NOT EXISTS oat_verification_testcase (
  id VARCHAR(64) PRIMARY KEY,
  baseline_id VARCHAR(64) NOT NULL,
  external_key VARCHAR(256) NOT NULL,
  title VARCHAR(512) NOT NULL,
  preconditions_text TEXT,
  steps_text TEXT,
  test_data_text TEXT,
  expected_text TEXT,
  requirement_refs TEXT,
  source_locator VARCHAR(512),
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_verification_testcase_baseline ON oat_verification_testcase (baseline_id, external_key);

CREATE TABLE IF NOT EXISTS oat_verification_trace_link (
  id VARCHAR(64) PRIMARY KEY,
  baseline_id VARCHAR(64) NOT NULL,
  source_type VARCHAR(32) NOT NULL,
  source_id VARCHAR(256) NOT NULL,
  target_type VARCHAR(32) NOT NULL,
  target_id VARCHAR(512) NOT NULL,
  relation_type VARCHAR(32) NOT NULL,
  generation_method VARCHAR(32) NOT NULL,
  confidence DECIMAL(5,4) NOT NULL,
  evidence_level VARCHAR(8) NOT NULL,
  review_status VARCHAR(32) NOT NULL,
  evidence_json JSONB,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_verification_trace_baseline_source ON oat_verification_trace_link (baseline_id, source_type, source_id);
CREATE INDEX IF NOT EXISTS idx_verification_trace_baseline_target ON oat_verification_trace_link (baseline_id, target_type, target_id);

CREATE TABLE IF NOT EXISTS oat_verification_finding (
  id VARCHAR(64) PRIMARY KEY,
  baseline_id VARCHAR(64) NOT NULL,
  ac_id VARCHAR(64),
  finding_type VARCHAR(64) NOT NULL,
  perspective VARCHAR(32) NOT NULL,
  severity VARCHAR(16) NOT NULL,
  title VARCHAR(512) NOT NULL,
  description_text TEXT NOT NULL,
  suggestion_text TEXT,
  confidence DECIMAL(5,4) NOT NULL,
  evidence_level VARCHAR(8) NOT NULL,
  verdict VARCHAR(32) NOT NULL,
  review_status VARCHAR(32) NOT NULL,
  evidence_json JSONB,
  external_work_item_url VARCHAR(1024),
  reviewed_by VARCHAR(64),
  review_reason TEXT,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_verification_finding_baseline ON oat_verification_finding (baseline_id, perspective, severity, review_status);
CREATE INDEX IF NOT EXISTS idx_verification_finding_ac ON oat_verification_finding (ac_id);

CREATE TABLE IF NOT EXISTS oat_verification_writeback_action (
  id VARCHAR(64) PRIMARY KEY,
  project_id VARCHAR(64) NOT NULL,
  baseline_id VARCHAR(64) NOT NULL,
  finding_id VARCHAR(64) NOT NULL,
  connector_type VARCHAR(64) NOT NULL,
  external_url VARCHAR(1024),
  status VARCHAR(32) NOT NULL,
  message TEXT,
  created_by VARCHAR(64),
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_verification_writeback_baseline ON oat_verification_writeback_action (baseline_id, create_time);
CREATE INDEX IF NOT EXISTS idx_verification_writeback_finding ON oat_verification_writeback_action (finding_id);

CREATE TABLE IF NOT EXISTS oat_verification_analysis_job (
  id VARCHAR(64) PRIMARY KEY,
  project_id VARCHAR(64) NOT NULL,
  baseline_id VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  message VARCHAR(2000),
  created_by VARCHAR(64),
  create_time TIMESTAMP NOT NULL,
  update_time TIMESTAMP NOT NULL,
  finish_time TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_verification_analysis_job_baseline ON oat_verification_analysis_job (project_id, baseline_id, create_time);
CREATE INDEX IF NOT EXISTS idx_verification_analysis_job_status ON oat_verification_analysis_job (project_id, baseline_id, status);
