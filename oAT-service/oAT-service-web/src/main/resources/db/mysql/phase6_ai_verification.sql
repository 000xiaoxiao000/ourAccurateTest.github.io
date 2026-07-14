-- Upgrade note:
-- If this script was already executed before execution_asset_id/coverage_asset_id/writeback table were added,
-- run the following once before re-running the application on an existing database:
-- ALTER TABLE oat_verification_baseline ADD COLUMN execution_asset_id VARCHAR(64) NULL AFTER source_asset_id;
-- ALTER TABLE oat_verification_baseline ADD COLUMN coverage_asset_id VARCHAR(64) NULL AFTER execution_asset_id;
-- MySQL versions differ on ADD COLUMN IF NOT EXISTS support, so these ALTER statements are documented
-- instead of embedded unconditionally.

CREATE TABLE IF NOT EXISTS `oat_verification_asset` (
  `id` VARCHAR(64) PRIMARY KEY,
  `project_id` VARCHAR(64) NOT NULL,
  `asset_type` VARCHAR(32) NOT NULL,
  `source_type` VARCHAR(32) NOT NULL,
  `external_id` VARCHAR(256),
  `external_url` VARCHAR(1024),
  `source_version` VARCHAR(256),
  `file_name` VARCHAR(512),
  `content_hash` VARCHAR(64) NOT NULL,
  `content_text` MEDIUMTEXT,
  `storage_type` VARCHAR(32) NOT NULL DEFAULT 'MYSQL',
  `storage_key` VARCHAR(512),
  `content_size` BIGINT NOT NULL DEFAULT 0,
  `content_preview` TEXT,
  `metadata_json` JSON,
  `freshness` VARCHAR(32) NOT NULL DEFAULT 'SNAPSHOT',
  `imported_by` VARCHAR(64),
  `captured_at` DATETIME NOT NULL,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX `idx_verification_asset_project_type` (`project_id`, `asset_type`, `create_time`),
  INDEX `idx_verification_asset_hash` (`project_id`, `content_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI验证外部资产快照';

CREATE TABLE IF NOT EXISTS `oat_verification_asset_content` (
  `storage_key` VARCHAR(512) PRIMARY KEY,
  `project_id` VARCHAR(64) NOT NULL,
  `asset_id` VARCHAR(64) NOT NULL,
  `content_hash` VARCHAR(64) NOT NULL,
  `content_text` MEDIUMTEXT,
  `content_size` BIGINT NOT NULL DEFAULT 0,
  `content_preview` TEXT,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX `idx_verification_asset_content_project` (`project_id`, `asset_id`),
  INDEX `idx_verification_asset_content_hash` (`project_id`, `content_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI验证资料正文存储-MySQL默认实现';

CREATE TABLE IF NOT EXISTS `oat_verification_baseline` (
  `id` VARCHAR(64) PRIMARY KEY,
  `project_id` VARCHAR(64) NOT NULL,
  `name` VARCHAR(256) NOT NULL,
  `requirement_asset_id` VARCHAR(64) NOT NULL,
  `testcase_asset_id` VARCHAR(64) NOT NULL,
  `source_asset_id` VARCHAR(64),
  `execution_asset_id` VARCHAR(64),
  `coverage_asset_id` VARCHAR(64),
  `source_app_id` VARCHAR(64),
  `repository_url` VARCHAR(1024),
  `source_branch` VARCHAR(512),
  `source_commit` VARCHAR(128),
  `analyzer_version` VARCHAR(64) NOT NULL,
  `status` VARCHAR(32) NOT NULL,
  `freshness` VARCHAR(32) NOT NULL,
  `created_by` VARCHAR(64),
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX `idx_verification_baseline_project_time` (`project_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI验证不可变分析基线';

CREATE TABLE IF NOT EXISTS `oat_verification_ac` (
  `id` VARCHAR(64) PRIMARY KEY,
  `baseline_id` VARCHAR(64) NOT NULL,
  `requirement_key` VARCHAR(256) NOT NULL,
  `ac_key` VARCHAR(256) NOT NULL,
  `title` VARCHAR(512),
  `content_text` TEXT NOT NULL,
  `source_locator` VARCHAR(512),
  `priority` VARCHAR(32),
  `testable` BOOLEAN NOT NULL DEFAULT TRUE,
  `ambiguity` BOOLEAN NOT NULL DEFAULT FALSE,
  `confidence` DECIMAL(5,4) NOT NULL DEFAULT 0,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX `idx_verification_ac_baseline` (`baseline_id`, `requirement_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI提取的验收标准投影';

CREATE TABLE IF NOT EXISTS `oat_verification_testcase` (
  `id` VARCHAR(64) PRIMARY KEY,
  `baseline_id` VARCHAR(64) NOT NULL,
  `external_key` VARCHAR(256) NOT NULL,
  `title` VARCHAR(512) NOT NULL,
  `preconditions_text` TEXT,
  `steps_text` MEDIUMTEXT,
  `test_data_text` TEXT,
  `expected_text` MEDIUMTEXT,
  `requirement_refs` TEXT,
  `source_locator` VARCHAR(512),
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX `idx_verification_testcase_baseline` (`baseline_id`, `external_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='测试用例只读分析投影';

CREATE TABLE IF NOT EXISTS `oat_verification_trace_link` (
  `id` VARCHAR(64) PRIMARY KEY,
  `baseline_id` VARCHAR(64) NOT NULL,
  `source_type` VARCHAR(32) NOT NULL,
  `source_id` VARCHAR(256) NOT NULL,
  `target_type` VARCHAR(32) NOT NULL,
  `target_id` VARCHAR(512) NOT NULL,
  `relation_type` VARCHAR(32) NOT NULL,
  `generation_method` VARCHAR(32) NOT NULL,
  `confidence` DECIMAL(5,4) NOT NULL,
  `evidence_level` VARCHAR(8) NOT NULL,
  `review_status` VARCHAR(32) NOT NULL,
  `evidence_json` JSON,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX `idx_verification_trace_baseline_source` (`baseline_id`, `source_type`, `source_id`),
  INDEX `idx_verification_trace_baseline_target` (`baseline_id`, `target_type`, `target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='需求用例代码双向追溯边';

CREATE TABLE IF NOT EXISTS `oat_verification_finding` (
  `id` VARCHAR(64) PRIMARY KEY,
  `baseline_id` VARCHAR(64) NOT NULL,
  `ac_id` VARCHAR(64),
  `finding_type` VARCHAR(64) NOT NULL,
  `perspective` VARCHAR(32) NOT NULL,
  `severity` VARCHAR(16) NOT NULL,
  `title` VARCHAR(512) NOT NULL,
  `description_text` TEXT NOT NULL,
  `suggestion_text` TEXT,
  `confidence` DECIMAL(5,4) NOT NULL,
  `evidence_level` VARCHAR(8) NOT NULL,
  `verdict` VARCHAR(32) NOT NULL,
  `review_status` VARCHAR(32) NOT NULL,
  `evidence_json` JSON,
  `external_work_item_url` VARCHAR(1024),
  `reviewed_by` VARCHAR(64),
  `review_reason` TEXT,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX `idx_verification_finding_baseline` (`baseline_id`, `perspective`, `severity`, `review_status`),
  INDEX `idx_verification_finding_ac` (`ac_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI一致性结构化发现';

CREATE TABLE IF NOT EXISTS `oat_verification_writeback_action` (
  `id` VARCHAR(64) PRIMARY KEY,
  `project_id` VARCHAR(64) NOT NULL,
  `baseline_id` VARCHAR(64) NOT NULL,
  `finding_id` VARCHAR(64) NOT NULL,
  `connector_type` VARCHAR(64) NOT NULL,
  `external_url` VARCHAR(1024),
  `status` VARCHAR(32) NOT NULL,
  `message` TEXT,
  `created_by` VARCHAR(64),
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX `idx_verification_writeback_baseline` (`baseline_id`, `create_time`),
  INDEX `idx_verification_writeback_finding` (`finding_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI验证外部回写动作审计';

CREATE TABLE IF NOT EXISTS `oat_verification_analysis_job` (
  `id` VARCHAR(64) PRIMARY KEY,
  `project_id` VARCHAR(64) NOT NULL,
  `baseline_id` VARCHAR(64) NOT NULL,
  `status` VARCHAR(32) NOT NULL,
  `message` VARCHAR(2000),
  `created_by` VARCHAR(64),
  `create_time` DATETIME NOT NULL,
  `update_time` DATETIME NOT NULL,
  `finish_time` DATETIME,
  INDEX `idx_verification_analysis_job_baseline` (`project_id`, `baseline_id`, `create_time`),
  INDEX `idx_verification_analysis_job_status` (`project_id`, `baseline_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI验证异步分析任务';
