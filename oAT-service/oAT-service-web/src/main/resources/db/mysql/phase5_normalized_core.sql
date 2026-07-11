CREATE TABLE IF NOT EXISTS `oat_user` (
  `id` VARCHAR(64) PRIMARY KEY,
  `name` VARCHAR(128),
  `nick_name` VARCHAR(128),
  `email` VARCHAR(128),
  `password` VARCHAR(256),
  `header` VARCHAR(512),
  `phone` VARCHAR(64),
  `readme` TEXT,
  `payload_json` JSON NOT NULL,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_email` (`email`),
  INDEX `idx_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

CREATE TABLE IF NOT EXISTS `oat_project` (
  `id` VARCHAR(64) PRIMARY KEY,
  `name` VARCHAR(128),
  `project_describe` TEXT,
  `owner_id` VARCHAR(64),
  `payload_json` JSON NOT NULL,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX `idx_owner` (`owner_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目表';

CREATE TABLE IF NOT EXISTS `oat_app` (
  `id` VARCHAR(64) PRIMARY KEY,
  `name` VARCHAR(128),
  `project_id` VARCHAR(64),
  `range_type` VARCHAR(32),
  `src_name` VARCHAR(256),
  `language` VARCHAR(32),
  `language_config_json` JSON,
  `app_describe` TEXT,
  `properties_text` TEXT,
  `current_version` VARCHAR(128),
  `current_branch` VARCHAR(256),
  `current_commit_id` VARCHAR(256),
  `repo_address` VARCHAR(512),
  `repo_user_name` VARCHAR(128),
  `repo_password` VARCHAR(256),
  `create_user_id` VARCHAR(64),
  `payload_json` JSON NOT NULL,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX `idx_project` (`project_id`),
  INDEX `idx_range` (`range_type`),
  INDEX `idx_current_version` (`current_version`, `current_commit_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用表';

CREATE TABLE IF NOT EXISTS `oat_label_group` (
  `id` VARCHAR(64) PRIMARY KEY,
  `project_id` VARCHAR(64),
  `label_type` VARCHAR(64),
  `payload_json` JSON NOT NULL,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX `idx_project_type` (`project_id`, `label_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标签组表';

CREATE TABLE IF NOT EXISTS `oat_project_member` (
  `id` VARCHAR(64) PRIMARY KEY,
  `project_id` VARCHAR(64) NOT NULL,
  `member_id` VARCHAR(64) NOT NULL,
  `payload_json` JSON NOT NULL,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_project_member` (`project_id`, `member_id`),
  INDEX `idx_member` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目成员表';

CREATE TABLE IF NOT EXISTS `oat_system_log` (
  `id` VARCHAR(64) PRIMARY KEY,
  `project_id` VARCHAR(64),
  `user_id` VARCHAR(64),
  `user_name` VARCHAR(128),
  `action` VARCHAR(128),
  `title` TEXT,
  `payload_json` JSON NOT NULL,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX `idx_project_time` (`project_id`, `update_time`),
  INDEX `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统日志表';

CREATE TABLE IF NOT EXISTS `oat_version` (
  `id` VARCHAR(64) PRIMARY KEY,
  `project_id` VARCHAR(64),
  `app_id` VARCHAR(64),
  `version_number` VARCHAR(128),
  `repo_branch` VARCHAR(256),
  `repo_commit_id` VARCHAR(256),
  `source_type` VARCHAR(64),
  `payload_json` JSON NOT NULL,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX `idx_project_app` (`project_id`, `app_id`, `create_time`),
  INDEX `idx_app_version` (`app_id`, `version_number`),
  INDEX `idx_app_branch_commit` (`app_id`, `repo_branch`, `repo_commit_id`),
  INDEX `idx_app_commit` (`app_id`, `repo_commit_id`),
  INDEX `idx_app_branch` (`app_id`, `repo_branch`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='版本表';

CREATE TABLE IF NOT EXISTS `oat_version_compare_report` (
  `id` VARCHAR(64) PRIMARY KEY,
  `project_id` VARCHAR(64),
  `app_id` VARCHAR(64),
  `job_id` VARCHAR(128),
  `job_name` VARCHAR(256),
  `job_log` TEXT,
  `source_version` TEXT,
  `target_version` TEXT,
  `git_branch` VARCHAR(512),
  `git_old_commit` VARCHAR(512),
  `git_new_commit` VARCHAR(512),
  `add_class_count` INT DEFAULT 0,
  `update_class_count` INT DEFAULT 0,
  `delete_class_count` INT DEFAULT 0,
  `add_method_count` INT DEFAULT 0,
  `update_method_count` INT DEFAULT 0,
  `delete_method_count` INT DEFAULT 0,
  `impact_case_count` INT DEFAULT 0,
  `differences_json` JSON,
  `cases_json` JSON,
  `payload_json` JSON NOT NULL,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX `idx_project_app_time` (`project_id`, `app_id`, `create_time`),
  INDEX `idx_job` (`job_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='版本比对报告表';

CREATE TABLE IF NOT EXISTS `oat_usecase` (
  `id` VARCHAR(64) PRIMARY KEY,
  `project_id` VARCHAR(64),
  `directory_id` VARCHAR(64),
  `title` VARCHAR(512),
  `content` MEDIUMTEXT,
  `head_image` VARCHAR(1024),
  `defects_json` JSON,
  `prd_requirements_json` JSON,
  `labels_json` JSON,
  `authors_json` JSON,
  `payload_json` JSON NOT NULL,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX `idx_project_time` (`project_id`, `create_time`),
  INDEX `idx_project_directory` (`project_id`, `directory_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用例表';

CREATE TABLE IF NOT EXISTS `oat_usecase_directory` (
  `id` VARCHAR(64) PRIMARY KEY,
  `project_id` VARCHAR(64),
  `parent_id` VARCHAR(64),
  `directory_name` VARCHAR(256),
  `payload_json` JSON NOT NULL,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX `idx_project_parent` (`project_id`, `parent_id`),
  INDEX `idx_project` (`project_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用例目录表';

CREATE TABLE IF NOT EXISTS `oat_static_source_class` (
  `id` VARCHAR(64) PRIMARY KEY,
  `app_id` VARCHAR(64) NOT NULL,
  `type` VARCHAR(32) NOT NULL DEFAULT 'classInfo',
  `class_id` VARCHAR(128),
  `class_name` VARCHAR(512) NOT NULL,
  `method_maps_json` JSON,
  `source_code` MEDIUMTEXT,
  `source_code_path` VARCHAR(1024),
  `source_code_hash` VARCHAR(128),
  `source_code_size` BIGINT,
  `payload_json` JSON NOT NULL,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_app_class` (`app_id`, `class_name`),
  INDEX `idx_app` (`app_id`),
  INDEX `idx_class_id` (`class_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='静态源码类信息表';
