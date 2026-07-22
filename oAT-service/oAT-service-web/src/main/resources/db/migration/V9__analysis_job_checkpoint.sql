-- Flyway V9: add checkpoint columns to oat_verification_analysis_job
--   checkpoint_step    : name of the last successfully completed step
--   checkpoint_payload : JSONB snapshot of intermediate state for resume
--   retry_count        : how many times this job has been retried
--   input_hash         : SHA-256 of (job_type + baseline_id + input params);
--                        used as idempotency key and cache-hit key
--   job_type           : logical job kind (INCREMENTAL_RECOMPUTE, READ_MODEL_REBUILD, …)
ALTER TABLE oat_verification_analysis_job
    ADD COLUMN IF NOT EXISTS job_type          VARCHAR(64),
    ADD COLUMN IF NOT EXISTS input_hash        VARCHAR(64),
    ADD COLUMN IF NOT EXISTS checkpoint_step   VARCHAR(128),
    ADD COLUMN IF NOT EXISTS checkpoint_payload JSONB,
    ADD COLUMN IF NOT EXISTS retry_count       INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS max_retries       INT NOT NULL DEFAULT 3;

-- Unique idempotency constraint: one active job per (baseline, job_type, input_hash)
CREATE UNIQUE INDEX IF NOT EXISTS uk_analysis_job_idempotency
    ON oat_verification_analysis_job (baseline_id, job_type, input_hash)
    WHERE status NOT IN ('SUCCEEDED', 'FAILED');
