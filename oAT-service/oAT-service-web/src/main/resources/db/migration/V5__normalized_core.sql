CREATE TABLE IF NOT EXISTS oat_user (
  id VARCHAR(64) PRIMARY KEY,
  name VARCHAR(128),
  nick_name VARCHAR(128),
  email VARCHAR(128),
  password VARCHAR(256),
  header VARCHAR(512),
  phone VARCHAR(64),
  readme TEXT,
  payload_json JSONB NOT NULL,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_oat_user_email ON oat_user (email);
CREATE INDEX IF NOT EXISTS idx_oat_user_name ON oat_user (name);

CREATE TABLE IF NOT EXISTS oat_project (
  id VARCHAR(64) PRIMARY KEY,
  name VARCHAR(128),
  project_describe TEXT,
  owner_id VARCHAR(64),
  payload_json JSONB NOT NULL,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_oat_project_owner ON oat_project (owner_id);

CREATE TABLE IF NOT EXISTS oat_app (
  id VARCHAR(64) PRIMARY KEY,
  name VARCHAR(128),
  project_id VARCHAR(64),
  range_type VARCHAR(32),
  src_name VARCHAR(256),
  language VARCHAR(32),
  language_config_json JSONB,
  app_describe TEXT,
  properties_text TEXT,
  current_version VARCHAR(128),
  current_branch VARCHAR(256),
  current_commit_id VARCHAR(256),
  repo_address VARCHAR(512),
  repo_user_name VARCHAR(128),
  repo_password VARCHAR(256),
  create_user_id VARCHAR(64),
  payload_json JSONB NOT NULL,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_oat_app_project ON oat_app (project_id);
CREATE INDEX IF NOT EXISTS idx_oat_app_range ON oat_app (range_type);
CREATE INDEX IF NOT EXISTS idx_oat_app_current_version ON oat_app (current_version, current_commit_id);

CREATE TABLE IF NOT EXISTS oat_label_group (
  id VARCHAR(64) PRIMARY KEY,
  project_id VARCHAR(64),
  label_type VARCHAR(64),
  payload_json JSONB NOT NULL,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_oat_label_group_project_type ON oat_label_group (project_id, label_type);

CREATE TABLE IF NOT EXISTS oat_project_member (
  id VARCHAR(64) PRIMARY KEY,
  project_id VARCHAR(64) NOT NULL,
  member_id VARCHAR(64) NOT NULL,
  payload_json JSONB NOT NULL,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_oat_project_member_project_member ON oat_project_member (project_id, member_id);
CREATE INDEX IF NOT EXISTS idx_oat_project_member_member ON oat_project_member (member_id);

CREATE TABLE IF NOT EXISTS oat_system_log (
  id VARCHAR(64) PRIMARY KEY,
  project_id VARCHAR(64),
  user_id VARCHAR(64),
  user_name VARCHAR(128),
  action VARCHAR(128),
  title TEXT,
  payload_json JSONB NOT NULL,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_oat_system_log_project_time ON oat_system_log (project_id, update_time);
CREATE INDEX IF NOT EXISTS idx_oat_system_log_user ON oat_system_log (user_id);

CREATE TABLE IF NOT EXISTS oat_version (
  id VARCHAR(64) PRIMARY KEY,
  project_id VARCHAR(64),
  app_id VARCHAR(64),
  version_number VARCHAR(128),
  repo_branch VARCHAR(256),
  repo_commit_id VARCHAR(256),
  source_type VARCHAR(64),
  payload_json JSONB NOT NULL,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_oat_version_project_app ON oat_version (project_id, app_id, create_time);
CREATE INDEX IF NOT EXISTS idx_oat_version_app_version ON oat_version (app_id, version_number);
CREATE INDEX IF NOT EXISTS idx_oat_version_app_branch_commit ON oat_version (app_id, repo_branch, repo_commit_id);
CREATE INDEX IF NOT EXISTS idx_oat_version_app_commit ON oat_version (app_id, repo_commit_id);
CREATE INDEX IF NOT EXISTS idx_oat_version_app_branch ON oat_version (app_id, repo_branch, create_time);

CREATE TABLE IF NOT EXISTS oat_version_compare_report (
  id VARCHAR(64) PRIMARY KEY,
  project_id VARCHAR(64),
  app_id VARCHAR(64),
  job_id VARCHAR(128),
  job_name VARCHAR(256),
  job_log TEXT,
  source_version TEXT,
  target_version TEXT,
  git_branch VARCHAR(512),
  git_old_commit VARCHAR(512),
  git_new_commit VARCHAR(512),
  add_class_count INT DEFAULT 0,
  update_class_count INT DEFAULT 0,
  delete_class_count INT DEFAULT 0,
  add_method_count INT DEFAULT 0,
  update_method_count INT DEFAULT 0,
  delete_method_count INT DEFAULT 0,
  impact_case_count INT DEFAULT 0,
  differences_json JSONB,
  cases_json JSONB,
  payload_json JSONB NOT NULL,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_oat_version_compare_project_app_time ON oat_version_compare_report (project_id, app_id, create_time);
CREATE INDEX IF NOT EXISTS idx_oat_version_compare_job ON oat_version_compare_report (job_id);

CREATE TABLE IF NOT EXISTS oat_usecase (
  id VARCHAR(64) PRIMARY KEY,
  project_id VARCHAR(64),
  directory_id VARCHAR(64),
  title VARCHAR(512),
  content TEXT,
  head_image VARCHAR(1024),
  defects_json JSONB,
  prd_requirements_json JSONB,
  labels_json JSONB,
  authors_json JSONB,
  payload_json JSONB NOT NULL,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_oat_usecase_project_time ON oat_usecase (project_id, create_time);
CREATE INDEX IF NOT EXISTS idx_oat_usecase_project_directory ON oat_usecase (project_id, directory_id);

CREATE TABLE IF NOT EXISTS oat_usecase_directory (
  id VARCHAR(64) PRIMARY KEY,
  project_id VARCHAR(64),
  parent_id VARCHAR(64),
  directory_name VARCHAR(256),
  payload_json JSONB NOT NULL,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_oat_usecase_directory_project_parent ON oat_usecase_directory (project_id, parent_id);
CREATE INDEX IF NOT EXISTS idx_oat_usecase_directory_project ON oat_usecase_directory (project_id);

CREATE TABLE IF NOT EXISTS oat_static_source_class (
  id VARCHAR(64) PRIMARY KEY,
  app_id VARCHAR(64) NOT NULL,
  type VARCHAR(32) NOT NULL DEFAULT 'classInfo',
  class_id VARCHAR(128),
  class_name VARCHAR(512) NOT NULL,
  method_maps_json JSONB,
  source_code TEXT,
  source_code_path VARCHAR(1024),
  source_code_hash VARCHAR(128),
  source_code_size BIGINT,
  payload_json JSONB NOT NULL,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_oat_static_source_class_app_class ON oat_static_source_class (app_id, class_name);
CREATE INDEX IF NOT EXISTS idx_oat_static_source_class_app ON oat_static_source_class (app_id);
CREATE INDEX IF NOT EXISTS idx_oat_static_source_class_class_id ON oat_static_source_class (class_id);

CREATE TABLE IF NOT EXISTS oat_class_coverage_index (
  id VARCHAR(64) PRIMARY KEY,
  report_id VARCHAR(64) NOT NULL,
  app_id VARCHAR(64) NOT NULL,
  class_name VARCHAR(512),
  source_type VARCHAR(64),
  language VARCHAR(64),
  display_name VARCHAR(512),
  source_path VARCHAR(1024) NOT NULL,
  total_methods INT DEFAULT 0,
  covered_methods INT DEFAULT 0,
  total_branches INT DEFAULT 0,
  covered_branches INT DEFAULT 0,
  total_branch_targets INT DEFAULT 0,
  covered_branch_targets INT DEFAULT 0,
  total_lines INT DEFAULT 0,
  covered_lines INT DEFAULT 0,
  total_complexity INT DEFAULT 0,
  line_rate DECIMAL(8,4),
  branch_rate DECIMAL(8,4),
  method_rate DECIMAL(8,4),
  payload_json JSONB NOT NULL,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_oat_class_coverage_report_path ON oat_class_coverage_index (report_id, source_path);
CREATE INDEX IF NOT EXISTS idx_oat_class_coverage_app ON oat_class_coverage_index (app_id);
CREATE INDEX IF NOT EXISTS idx_oat_class_coverage_report ON oat_class_coverage_index (report_id);
