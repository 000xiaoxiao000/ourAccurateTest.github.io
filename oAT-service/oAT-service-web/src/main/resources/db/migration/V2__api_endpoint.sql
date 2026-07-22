CREATE TABLE IF NOT EXISTS oat_api_endpoint (
  id VARCHAR(64) PRIMARY KEY,
  app_id VARCHAR(64) NOT NULL,
  source_type VARCHAR(64),
  source_name VARCHAR(256),
  source_names TEXT,
  source_type_names TEXT,
  endpoint_type VARCHAR(32),
  url VARCHAR(512),
  http_method VARCHAR(16),
  class_name VARCHAR(256),
  class_names TEXT,
  method_name VARCHAR(128),
  method_names TEXT,
  method_desc VARCHAR(512),
  method_descs TEXT,
  covered BOOLEAN DEFAULT FALSE,
  hit_count INT DEFAULT 0,
  merged_source_count INT DEFAULT 0,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_oat_api_endpoint_app_sort ON oat_api_endpoint (app_id, endpoint_type, url);
CREATE INDEX IF NOT EXISTS idx_oat_api_endpoint_app_covered ON oat_api_endpoint (app_id, covered);
CREATE INDEX IF NOT EXISTS idx_oat_api_endpoint_app_class_method ON oat_api_endpoint (app_id, class_name, method_name);
