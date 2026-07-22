package com.oAT.web.verification.graph;

import com.oAT.web.verification.VerificationRepository;
import com.oAT.web.verification.model.VerificationModels.AnalysisJob;
import com.oAT.web.verification.model.VerificationModels.AnalysisJobStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnalysisJobServiceTest {

    private VerificationRepository verificationRepository;
    private AnalysisJobService service;

    private static final String PROJECT  = "proj-1";
    private static final String BASELINE = "bl-1";
    private static final String JOB_TYPE = AnalysisJobService.JOB_INCREMENTAL_RECOMPUTE;

    @BeforeEach
    void setUp() {
        verificationRepository = mock(VerificationRepository.class);
        service = new AnalysisJobService(verificationRepository);
        doNothing().when(verificationRepository).saveAnalysisJob(any());
    }

    // ── submit ────────────────────────────────────────────────────────────────

    @Test
    void submit_new_job_persists_and_returns_ENQUEUED() {
        String hash = AnalysisJobService.inputHash(JOB_TYPE, BASELINE, "sym.A");
        when(verificationRepository.findSucceededJobByInputHash(BASELINE, JOB_TYPE, hash))
                .thenReturn(Optional.empty());
        when(verificationRepository.findActiveJobByInputHash(BASELINE, JOB_TYPE, hash))
                .thenReturn(Optional.empty());

        var result = service.submit(PROJECT, BASELINE, JOB_TYPE, hash, "system");

        assertEquals(AnalysisJobService.SubmitOutcome.ENQUEUED, result.outcome());
        assertNotNull(result.jobId());
        assertTrue(result.isNew());
        verify(verificationRepository).saveAnalysisJob(argThat(j ->
                j.status() == AnalysisJobStatus.QUEUED && JOB_TYPE.equals(j.jobType())));
    }

    @Test
    void submit_cache_hit_returns_CACHE_HIT_without_saving() {
        String hash = AnalysisJobService.inputHash(JOB_TYPE, BASELINE, "sym.A");
        AnalysisJob cached = succeededJob("job-cached", hash);
        when(verificationRepository.findSucceededJobByInputHash(BASELINE, JOB_TYPE, hash))
                .thenReturn(Optional.of(cached));

        var result = service.submit(PROJECT, BASELINE, JOB_TYPE, hash, "system");

        assertTrue(result.isCacheHit());
        assertEquals("job-cached", result.jobId());
        verify(verificationRepository, never()).saveAnalysisJob(any());
    }

    @Test
    void submit_deduplicates_existing_active_job() {
        String hash = AnalysisJobService.inputHash(JOB_TYPE, BASELINE, "sym.A");
        when(verificationRepository.findSucceededJobByInputHash(BASELINE, JOB_TYPE, hash))
                .thenReturn(Optional.empty());
        AnalysisJob active = runningJob("job-running", hash);
        when(verificationRepository.findActiveJobByInputHash(BASELINE, JOB_TYPE, hash))
                .thenReturn(Optional.of(active));

        var result = service.submit(PROJECT, BASELINE, JOB_TYPE, hash, "system");

        assertEquals(AnalysisJobService.SubmitOutcome.DEDUPLICATED, result.outcome());
        assertEquals("job-running", result.jobId());
        verify(verificationRepository, never()).saveAnalysisJob(any());
    }

    @Test
    void submit_different_input_hash_creates_new_job() {
        String hash1 = AnalysisJobService.inputHash(JOB_TYPE, BASELINE, "sym.A");
        String hash2 = AnalysisJobService.inputHash(JOB_TYPE, BASELINE, "sym.B");
        assertNotEquals(hash1, hash2);

        when(verificationRepository.findSucceededJobByInputHash(eq(BASELINE), eq(JOB_TYPE), anyString()))
                .thenReturn(Optional.empty());
        when(verificationRepository.findActiveJobByInputHash(eq(BASELINE), eq(JOB_TYPE), anyString()))
                .thenReturn(Optional.empty());

        var r1 = service.submit(PROJECT, BASELINE, JOB_TYPE, hash1, "system");
        var r2 = service.submit(PROJECT, BASELINE, JOB_TYPE, hash2, "system");

        assertNotEquals(r1.jobId(), r2.jobId());
        assertEquals(AnalysisJobService.SubmitOutcome.ENQUEUED, r1.outcome());
        assertEquals(AnalysisJobService.SubmitOutcome.ENQUEUED, r2.outcome());
    }

    // ── checkpoint ────────────────────────────────────────────────────────────

    @Test
    void checkpoint_delegates_to_repository() {
        doNothing().when(verificationRepository).saveCheckpoint(anyString(), anyString(), any());

        service.checkpoint("job-1", "STEP_A", Map.of("count", 5));

        verify(verificationRepository).saveCheckpoint("job-1", "STEP_A", Map.of("count", 5));
    }

    // ── succeed / fail ────────────────────────────────────────────────────────

    @Test
    void succeed_updates_status_to_SUCCEEDED() {
        doNothing().when(verificationRepository)
                .updateAnalysisJobStatus(anyString(), any(), any(), any());

        service.succeed("job-1");

        verify(verificationRepository).updateAnalysisJobStatus(
                eq("job-1"), eq(AnalysisJobStatus.SUCCEEDED), isNull(), notNull());
    }

    @Test
    void fail_delegates_failAndScheduleRetry() {
        doNothing().when(verificationRepository).failAndScheduleRetry(anyString(), anyString());

        service.fail("job-1", "NPE");

        verify(verificationRepository).failAndScheduleRetry("job-1", "NPE");
    }

    // ── inputHash ─────────────────────────────────────────────────────────────

    @Test
    void inputHash_is_deterministic_and_64_hex_chars() {
        String h1 = AnalysisJobService.inputHash(JOB_TYPE, BASELINE, "sym.A", "sym.B");
        String h2 = AnalysisJobService.inputHash(JOB_TYPE, BASELINE, "sym.A", "sym.B");
        assertEquals(h1, h2);
        assertEquals(64, h1.length());
    }

    @Test
    void inputHash_differs_when_inputs_differ() {
        String h1 = AnalysisJobService.inputHash(JOB_TYPE, BASELINE, "sym.A");
        String h2 = AnalysisJobService.inputHash(JOB_TYPE, BASELINE, "sym.B");
        assertNotEquals(h1, h2);
    }

    @Test
    void inputHash_differs_when_jobType_differs() {
        String h1 = AnalysisJobService.inputHash(JOB_TYPE, BASELINE, "sym.A");
        String h2 = AnalysisJobService.inputHash(AnalysisJobService.JOB_READ_MODEL_REBUILD, BASELINE, "sym.A");
        assertNotEquals(h1, h2);
    }

    // ── claimNext ─────────────────────────────────────────────────────────────

    @Test
    void claimNext_delegates_to_repository() {
        AnalysisJob queued = queuedJob("job-q", "hash-q");
        when(verificationRepository.claimNextPendingJob(JOB_TYPE)).thenReturn(Optional.of(queued));

        Optional<AnalysisJob> claimed = service.claimNext(JOB_TYPE);

        assertTrue(claimed.isPresent());
        assertEquals("job-q", claimed.get().id());
    }

    @Test
    void claimNext_returns_empty_when_no_pending_jobs() {
        when(verificationRepository.claimNextPendingJob(JOB_TYPE)).thenReturn(Optional.empty());

        assertTrue(service.claimNext(JOB_TYPE).isEmpty());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private AnalysisJob succeededJob(String id, String inputHash) {
        return new AnalysisJob(id, PROJECT, BASELINE, JOB_TYPE, inputHash,
                AnalysisJobStatus.SUCCEEDED, null, "system",
                LocalDateTime.now().minusMinutes(5), LocalDateTime.now(), LocalDateTime.now(),
                "MARK_ACS", Map.of("affectedNodeCount", 3), 0, 3);
    }

    private AnalysisJob runningJob(String id, String inputHash) {
        return new AnalysisJob(id, PROJECT, BASELINE, JOB_TYPE, inputHash,
                AnalysisJobStatus.RUNNING, null, "system",
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now(), null,
                "WALK_SUBTREE", Map.of(), 0, 3);
    }

    private AnalysisJob queuedJob(String id, String inputHash) {
        return new AnalysisJob(id, PROJECT, BASELINE, JOB_TYPE, inputHash,
                AnalysisJobStatus.QUEUED, null, "system",
                LocalDateTime.now(), LocalDateTime.now(), null,
                null, Map.of(), 0, 3);
    }
}
