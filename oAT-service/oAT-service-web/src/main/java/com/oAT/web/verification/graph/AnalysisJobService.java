package com.oAT.web.verification.graph;

import com.oAT.web.logging.LogFields;
import com.oAT.web.verification.VerificationRepository;
import com.oAT.web.verification.model.GraphModels;
import com.oAT.web.verification.model.VerificationModels.AnalysisJob;
import com.oAT.web.verification.model.VerificationModels.AnalysisJobStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages the lifecycle of incremental analysis jobs:
 *
 *  1. Idempotent submit — if a job with the same (baseline, jobType, inputHash) is already
 *     active or succeeded, the existing job id is returned instead of creating a duplicate.
 *  2. Cache-hit skip — if a SUCCEEDED job with the same inputHash exists, callers can skip
 *     re-computation entirely and return the cached result.
 *  3. Atomic claim — uses FOR UPDATE SKIP LOCKED so multiple scheduler threads never double-claim
 *     the same job row.
 *  4. Per-step checkpoint — after each logical step the service persists the step name and an
 *     intermediate-state payload. On restart, the executor resumes from the last checkpoint
 *     instead of restarting from scratch.
 *  5. Auto-retry — on failure the job is re-queued up to maxRetries times; after that it is
 *     permanently FAILED.
 */
@Service
public class AnalysisJobService {
    private static final Logger log = LoggerFactory.getLogger(AnalysisJobService.class);

    public static final String JOB_INCREMENTAL_RECOMPUTE = "INCREMENTAL_RECOMPUTE";
    public static final String JOB_READ_MODEL_REBUILD    = "READ_MODEL_REBUILD";
    public static final String JOB_AC_FUSION_REBUILD     = "AC_FUSION_REBUILD";

    private final VerificationRepository verificationRepository;

    public AnalysisJobService(VerificationRepository verificationRepository) {
        this.verificationRepository = verificationRepository;
    }

    // ── Submit ────────────────────────────────────────────────────────────────

    /**
     * Idempotently enqueue a job. Returns the job id (new or existing).
     * If a SUCCEEDED job with the same inputHash already exists, sets the result to CACHE_HIT
     * so the caller can skip execution.
     */
    public SubmitResult submit(String projectId, String baselineId, String jobType,
                               String inputHash, String createdBy) {
        // Cache hit: a completed job with identical input already exists
        Optional<AnalysisJob> cached = verificationRepository.findSucceededJobByInputHash(
                baselineId, jobType, inputHash);
        if (cached.isPresent()) {
            log.debug("event=analysis_job.cache_hit {}", LogFields.of(LogFields.map(
                    "job_type", jobType,
                    "baseline_id", baselineId,
                    "input_hash", inputHash)));
            return new SubmitResult(cached.get().id(), SubmitOutcome.CACHE_HIT, cached.get());
        }

        // Idempotency: an active job for this input is already running / queued
        Optional<AnalysisJob> active = verificationRepository.findActiveJobByInputHash(
                baselineId, jobType, inputHash);
        if (active.isPresent()) {
            log.debug("event=analysis_job.deduplicated {}", LogFields.of(LogFields.map(
                    "job_type", jobType,
                    "baseline_id", baselineId,
                    "input_hash", inputHash,
                    "existing_job_id", active.get().id())));
            return new SubmitResult(active.get().id(), SubmitOutcome.DEDUPLICATED, active.get());
        }

        // New job
        String jobId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        AnalysisJob job = new AnalysisJob(jobId, projectId, baselineId, jobType, inputHash,
                AnalysisJobStatus.QUEUED, null, createdBy, now, now, null, null, Map.of(), 0, 3);
        verificationRepository.saveAnalysisJob(job);
        log.info("event=analysis_job.enqueued {}", LogFields.of(LogFields.map(
                "project_id", projectId,
                "baseline_id", baselineId,
                "job_id", jobId,
                "job_type", jobType,
                "input_hash", inputHash,
                "created_by", createdBy)));
        return new SubmitResult(jobId, SubmitOutcome.ENQUEUED, job);
    }

    // ── Claim ─────────────────────────────────────────────────────────────────

    /**
     * Atomically claim the next QUEUED job of the given type.
     * Safe to call from multiple threads/instances concurrently.
     */
    public Optional<AnalysisJob> claimNext(String jobType) {
        return verificationRepository.claimNextPendingJob(jobType);
    }

    // ── Checkpoint ────────────────────────────────────────────────────────────

    /**
     * Persist a named checkpoint after a successfully completed step.
     * On resume the executor checks this name and skips already-done steps.
     */
    public void checkpoint(String jobId, String stepName, Map<String, Object> payload) {
        verificationRepository.saveCheckpoint(jobId, stepName, payload);
        log.debug("event=analysis_job.checkpoint_saved {}", LogFields.of(LogFields.map(
                "job_id", jobId,
                "step", stepName)));
    }

    // ── Complete / Fail ───────────────────────────────────────────────────────

    public void succeed(String jobId) {
        verificationRepository.updateAnalysisJobStatus(jobId, AnalysisJobStatus.SUCCEEDED, null, LocalDateTime.now());
        log.info("event=analysis_job.succeeded {}", LogFields.of(LogFields.map("job_id", jobId)));
    }

    public void fail(String jobId, String reason) {
        verificationRepository.failAndScheduleRetry(jobId, reason);
        log.warn("event=analysis_job.failed {}", LogFields.of(LogFields.map(
                "job_id", jobId,
                "reason", reason)));
    }

    // ── Input hash helpers ────────────────────────────────────────────────────

    /**
     * Compute a stable cache key from the job type, baseline id, and a variable-length list of
     * input tokens (e.g. changed symbol names, graph version hashes, prompt version).
     */
    public static String inputHash(String jobType, String baselineId, String... inputs) {
        StringBuilder sb = new StringBuilder(jobType).append('|').append(baselineId);
        for (String input : inputs) {
            sb.append('|').append(input == null ? "" : input);
        }
        return GraphModels.fingerprint(sb.toString());
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    public enum SubmitOutcome { ENQUEUED, DEDUPLICATED, CACHE_HIT }

    public record SubmitResult(String jobId, SubmitOutcome outcome, AnalysisJob job) {
        public boolean isCacheHit() { return outcome == SubmitOutcome.CACHE_HIT; }
        public boolean isNew()      { return outcome == SubmitOutcome.ENQUEUED; }
    }
}
