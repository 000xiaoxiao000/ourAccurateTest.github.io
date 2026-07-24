CREATE TABLE IF NOT EXISTS oat_git_impact_job (
    id VARCHAR(64) PRIMARY KEY,
    project_id VARCHAR(64) NOT NULL,
    baseline_id VARCHAR(64) NOT NULL,
    app_id VARCHAR(64) NOT NULL,
    base_commit VARCHAR(128) NOT NULL,
    head_commit VARCHAR(128) NOT NULL,
    created_by VARCHAR(64),
    status VARCHAR(32) NOT NULL,
    stage VARCHAR(64) NOT NULL,
    percent INTEGER NOT NULL,
    message VARCHAR(2000),
    result_json JSONB,
    error_message TEXT,
    create_time TIMESTAMP NOT NULL,
    update_time TIMESTAMP NOT NULL,
    finish_time TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_git_impact_job_project_status
    ON oat_git_impact_job (project_id, status, create_time);
CREATE INDEX IF NOT EXISTS idx_git_impact_job_baseline
    ON oat_git_impact_job (baseline_id, create_time DESC);
CREATE UNIQUE INDEX IF NOT EXISTS uk_git_impact_job_active_request
    ON oat_git_impact_job (project_id, baseline_id, app_id, base_commit, head_commit)
    WHERE status IN ('PENDING', 'RUNNING');

CREATE TABLE IF NOT EXISTS oat_git_impact_llm_review (
    report_id VARCHAR(64) PRIMARY KEY,
    status VARCHAR(32) NOT NULL,
    total INTEGER NOT NULL,
    completed INTEGER NOT NULL,
    judgements_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    message TEXT,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
