-- Flyway V12: quality gate enforcement mode persistence + stale commit detection support
--
-- §7 plan: gate result needs to record the enforcement mode (SHADOW/SOFT/HARD) and
-- whether the build was actually blocked, so audit logs answer "was this gate a
-- shadow run or a real block?".
-- §13 plan: runtime_execution_commit_check view lets the gate rule
-- requireChangeImpactVerified query whether any runtime evidence uses a commit
-- that differs from the baseline source_commit.

-- ── Quality gate result: enforcement mode + blocked flag ──────────────────────
ALTER TABLE oat_quality_gate_result
    ADD COLUMN IF NOT EXISTS enforcement_mode VARCHAR(16) NOT NULL DEFAULT 'HARD',
    ADD COLUMN IF NOT EXISTS blocked          BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS rationale        TEXT;

-- ── Quality gate policy: enforcement_mode default ────────────────────────────
ALTER TABLE oat_quality_gate_policy
    ADD COLUMN IF NOT EXISTS enforcement_mode VARCHAR(16) NOT NULL DEFAULT 'HARD';

-- ── View: detect stale runtime evidence (commit mismatch) ─────────────────────
-- Used by requireChangeImpactVerified gate rule to find baselines where any
-- runtime execution was captured against a different source commit.
CREATE OR REPLACE VIEW v_stale_runtime_evidence AS
SELECT
    b.id            AS baseline_id,
    b.project_id,
    b.source_commit AS expected_commit,
    e.source_commit AS actual_commit,
    e.id            AS execution_id,
    e.captured_at
FROM oat_verification_baseline    b
JOIN oat_runtime_test_execution    e
    ON  e.baseline_id  = b.id
    AND e.source_commit IS NOT NULL
    AND e.source_commit <> b.source_commit
WHERE b.source_commit IS NOT NULL;

-- ── Index: quality gate results lookup ───────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_gate_result_baseline
    ON oat_quality_gate_result (project_id, baseline_id, evaluated_at DESC);
