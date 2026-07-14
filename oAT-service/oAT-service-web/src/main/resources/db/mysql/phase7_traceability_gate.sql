-- phase7: traceability, quality gate, connector config
-- Run after phase6_ai_verification.sql

CREATE TABLE IF NOT EXISTS `oat_connector_config` (
  `id`                  VARCHAR(64)  PRIMARY KEY,
  `project_id`          VARCHAR(64)  NOT NULL,
  `name`                VARCHAR(256) NOT NULL,
  `connector_type`      VARCHAR(64)  NOT NULL,
  `base_url`            VARCHAR(1024),
  `credential_hint`     VARCHAR(256),
  `field_mapping_json`  JSON,
  `write_back_enabled`  BOOLEAN      NOT NULL DEFAULT FALSE,
  `status`              VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
  `created_by`          VARCHAR(64),
  `create_time`         DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `update_time`         DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX `idx_connector_project` (`project_id`, `connector_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='外部系统连接器配置';

CREATE TABLE IF NOT EXISTS `oat_change_impact_report` (
  `id`                 VARCHAR(64)  PRIMARY KEY,
  `project_id`         VARCHAR(64)  NOT NULL,
  `baseline_id`        VARCHAR(64)  NOT NULL,
  `change_description` VARCHAR(1024),
  `impacted_ac_count`  INT          NOT NULL DEFAULT 0,
  `impacted_tc_count`  INT          NOT NULL DEFAULT 0,
  `orphan_count`       INT          NOT NULL DEFAULT 0,
  `report_json`        JSON,
  `created_by`         VARCHAR(64),
  `create_time`        DATETIME     DEFAULT CURRENT_TIMESTAMP,
  INDEX `idx_change_impact_baseline` (`project_id`, `baseline_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='变更影响分析报告';

CREATE TABLE IF NOT EXISTS `oat_quality_gate_policy` (
  `id`                                VARCHAR(64)    PRIMARY KEY,
  `project_id`                        VARCHAR(64)    NOT NULL,
  `name`                              VARCHAR(256)   NOT NULL,
  `min_testcase_coverage_rate`        DECIMAL(5,4)   NOT NULL DEFAULT 0.8,
  `min_implementation_coverage_rate`  DECIMAL(5,4)   NOT NULL DEFAULT 0.7,
  `max_critical_findings`             INT            NOT NULL DEFAULT 0,
  `max_high_findings`                 INT            NOT NULL DEFAULT 3,
  `require_all_ambiguities_resolved`  BOOLEAN        NOT NULL DEFAULT FALSE,
  `require_change_impact_verified`    BOOLEAN        NOT NULL DEFAULT FALSE,
  `block_on_stale_baseline`           BOOLEAN        NOT NULL DEFAULT TRUE,
  `created_by`                        VARCHAR(64),
  `create_time`                       DATETIME       DEFAULT CURRENT_TIMESTAMP,
  `update_time`                       DATETIME       DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX `idx_gate_policy_project` (`project_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='质量门禁策略';

CREATE TABLE IF NOT EXISTS `oat_quality_gate_result` (
  `id`             VARCHAR(64)  PRIMARY KEY,
  `project_id`     VARCHAR(64)  NOT NULL,
  `baseline_id`    VARCHAR(64)  NOT NULL,
  `policy_id`      VARCHAR(64)  NOT NULL,
  `verdict`        VARCHAR(32)  NOT NULL,
  `failures_count` INT          NOT NULL DEFAULT 0,
  `evaluated_by`   VARCHAR(64),
  `evaluated_at`   DATETIME     NOT NULL,
  INDEX `idx_gate_result_baseline` (`project_id`, `baseline_id`, `evaluated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='质量门禁评估结果';

CREATE TABLE IF NOT EXISTS `oat_quality_gate_exemption` (
  `id`          VARCHAR(64)  PRIMARY KEY,
  `project_id`  VARCHAR(64)  NOT NULL,
  `baseline_id` VARCHAR(64)  NOT NULL,
  `rule_id`     VARCHAR(128) NOT NULL,
  `reason`      TEXT         NOT NULL,
  `granted_by`  VARCHAR(64),
  `expires_at`  DATETIME,
  `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
  INDEX `idx_gate_exemption_baseline` (`project_id`, `baseline_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='质量门禁豁免记录';
