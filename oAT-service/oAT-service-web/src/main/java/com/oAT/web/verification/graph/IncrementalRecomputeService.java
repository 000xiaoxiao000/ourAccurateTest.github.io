package com.oAT.web.verification.graph;

import com.oAT.web.logging.LogFields;
import com.oAT.web.verification.VerificationRepository;
import com.oAT.web.verification.model.GraphModels;
import com.oAT.web.verification.model.GraphModels.GraphNodeKind;
import com.oAT.web.verification.model.VerificationModels.TraceLink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Incremental subgraph recompute. Steps:
 *
 *   STEP_LOCATE_ROOTS   — find method graph nodes matching changed symbols
 *   STEP_WALK_SUBTREE   — walk bounded CALLS/CONTAINS subgraph around roots
 *   STEP_INVALIDATE     — invalidate only the affected subgraph nodes+edges
 *   STEP_MARK_ACS       — determine affected ACs and mark their aggregates STALE
 *
 * Each step is checkpointed so a crash/restart resumes from the last saved step.
 * The whole execution is wrapped in an AnalysisJobService job for idempotency and caching.
 */
@Service
public class IncrementalRecomputeService {
    private static final Logger log = LoggerFactory.getLogger(IncrementalRecomputeService.class);

    public static final String AGGREGATE_KIND    = "INCREMENTAL_RECOMPUTE";
    private static final int   SUBGRAPH_DEPTH    = 3;

    static final String STEP_LOCATE_ROOTS = "LOCATE_ROOTS";
    static final String STEP_WALK_SUBTREE = "WALK_SUBTREE";
    static final String STEP_INVALIDATE   = "INVALIDATE";
    static final String STEP_MARK_ACS     = "MARK_ACS";

    private final GraphRepository graphRepository;
    private final VerificationRepository verificationRepository;
    private final AnalysisJobService jobService;

    public IncrementalRecomputeService(GraphRepository graphRepository,
                                       VerificationRepository verificationRepository,
                                       AnalysisJobService jobService) {
        this.graphRepository = graphRepository;
        this.verificationRepository = verificationRepository;
        this.jobService = jobService;
    }

    /**
     * Idempotent entry point. Returns immediately with cached result if the same
     * (baselineId + changedSymbols) was already successfully processed.
     */
    public RecomputeResult recompute(String projectId, String baselineId, List<String> changedSymbols) {
        verificationRepository.findBaseline(projectId, baselineId)
                .orElseThrow(() -> new IllegalArgumentException("分析基线不存在或不属于当前项目"));
        if (changedSymbols == null || changedSymbols.isEmpty()) {
            throw new IllegalArgumentException("变更符号列表不能为空");
        }

        String inputHash = AnalysisJobService.inputHash(
                AnalysisJobService.JOB_INCREMENTAL_RECOMPUTE, baselineId,
                changedSymbols.stream().sorted().toArray(String[]::new));

        AnalysisJobService.SubmitResult submit = jobService.submit(
                projectId, baselineId, AnalysisJobService.JOB_INCREMENTAL_RECOMPUTE,
                inputHash, "system");

        if (submit.isCacheHit()) {
            log.info("event=incremental_recompute.cache_hit {}", LogFields.of(LogFields.map(
                    "project_id", projectId,
                    "baseline_id", baselineId,
                    "input_hash", inputHash)));
            return resultFromCheckpoint(submit.job().checkpointPayload());
        }
        if (!submit.isNew()) {
            log.info("event=incremental_recompute.deduplicated {}", LogFields.of(LogFields.map(
                    "project_id", projectId,
                    "baseline_id", baselineId,
                    "job_id", submit.jobId())));
            return resultFromCheckpoint(submit.job().checkpointPayload());
        }

        return executeWithCheckpoints(submit.jobId(), projectId, baselineId, changedSymbols);
    }

    private RecomputeResult executeWithCheckpoints(String jobId, String projectId,
                                                   String baselineId, List<String> changedSymbols) {
        try {
            // ── STEP 1: locate root nodes ─────────────────────────────────────
            Set<String> rootIds = new LinkedHashSet<>();
            Set<String> matchedSymbols = new LinkedHashSet<>();
            for (String symbol : changedSymbols) {
                if (!StringUtils.hasText(symbol)) continue;
                for (GraphRepository.GraphNode node : graphRepository.findNodesBySymbolPrefix(
                        baselineId, GraphNodeKind.METHOD, symbol)) {
                    rootIds.add(node.id());
                    if (StringUtils.hasText(node.stableSymbolId())) matchedSymbols.add(node.stableSymbolId());
                }
            }
            jobService.checkpoint(jobId, STEP_LOCATE_ROOTS, Map.of(
                    "rootCount", rootIds.size(), "matchedSymbols", new ArrayList<>(matchedSymbols)));

            // ── STEP 2: walk bounded subgraph ─────────────────────────────────
            List<GraphRepository.GraphNode> subtree = graphRepository.findMethodSubtree(
                    baselineId, new ArrayList<>(rootIds), SUBGRAPH_DEPTH);
            List<String> affectedNodeIds = subtree.stream().map(GraphRepository.GraphNode::id).toList();
            Set<String> affectedSymbols = new LinkedHashSet<>(matchedSymbols);
            subtree.forEach(n -> { if (StringUtils.hasText(n.stableSymbolId())) affectedSymbols.add(n.stableSymbolId()); });
            jobService.checkpoint(jobId, STEP_WALK_SUBTREE, Map.of(
                    "affectedNodeCount", affectedNodeIds.size(),
                    "affectedSymbols", new ArrayList<>(affectedSymbols)));

            // ── STEP 3: invalidate only the affected subgraph ─────────────────
            graphRepository.invalidateNodeSubtree(baselineId, affectedNodeIds);
            jobService.checkpoint(jobId, STEP_INVALIDATE, Map.of("invalidatedCount", affectedNodeIds.size()));

            // ── STEP 4: mark affected ACs and their aggregates STALE ─────────
            List<TraceLink> links = verificationRepository.findTraceLinks(baselineId);
            Set<String> affectedAcIds = new LinkedHashSet<>();
            for (TraceLink link : links) {
                if (!"SOURCE_SYMBOL".equals(link.targetType())) continue;
                boolean hit = affectedSymbols.stream().anyMatch(
                        s -> s.contains(link.targetId()) || link.targetId().contains(s));
                if (hit) affectedAcIds.add(link.sourceId());
            }
            List<String> affectedTestcaseIds = links.stream()
                    .filter(l -> affectedAcIds.contains(l.sourceId()) && "TESTCASE".equals(l.targetType()))
                    .map(TraceLink::targetId).distinct().toList();
            int staleAggregates = 0;
            for (String acId : affectedAcIds) {
                staleAggregates += graphRepository.invalidateAggregateSubject(
                        baselineId, AcceptanceCriterionFusionService.AGGREGATE_KIND, acId);
                staleAggregates += graphRepository.invalidateAggregateSubject(
                        baselineId, AssertionConsistencyService.AGGREGATE_KIND, acId);
            }

            Map<String, Object> finalPayload = Map.of(
                    "changedSymbols",    changedSymbols,
                    "matchedRootCount",  rootIds.size(),
                    "affectedNodeCount", affectedNodeIds.size(),
                    "affectedSymbols",   new ArrayList<>(affectedSymbols),
                    "affectedAcIds",     new ArrayList<>(affectedAcIds),
                    "affectedTestcaseIds", affectedTestcaseIds,
                    "staleAggregates",   staleAggregates);
            jobService.checkpoint(jobId, STEP_MARK_ACS, finalPayload);

            // Persist audit aggregate and mark job SUCCEEDED
            String sourceHash = GraphModels.fingerprint(
                    String.join("|", changedSymbols) + "|" + affectedNodeIds.size());
            graphRepository.saveAggregate(new GraphRepository.GraphAggregate(
                    "incremental:" + GraphModels.fingerprint(baselineId + "|" + sourceHash),
                    baselineId, projectId, AGGREGATE_KIND, sourceHash, sourceHash, finalPayload));

            jobService.succeed(jobId);
            return new RecomputeResult(new ArrayList<>(affectedSymbols), affectedNodeIds.size(),
                    new ArrayList<>(affectedAcIds), affectedTestcaseIds, staleAggregates);

        } catch (RuntimeException ex) {
            jobService.fail(jobId, ex.getMessage());
            throw ex;
        }
    }

    @SuppressWarnings("unchecked")
    private RecomputeResult resultFromCheckpoint(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) return new RecomputeResult(List.of(), 0, List.of(), List.of(), 0);
        List<String> affectedSymbols  = (List<String>) payload.getOrDefault("affectedSymbols", List.of());
        int affectedNodeCount         = ((Number) payload.getOrDefault("affectedNodeCount", 0)).intValue();
        List<String> affectedAcIds    = (List<String>) payload.getOrDefault("affectedAcIds", List.of());
        List<String> affectedTcIds    = (List<String>) payload.getOrDefault("affectedTestcaseIds", List.of());
        int staleAggregates           = ((Number) payload.getOrDefault("staleAggregates", 0)).intValue();
        return new RecomputeResult(affectedSymbols, affectedNodeCount, affectedAcIds, affectedTcIds, staleAggregates);
    }

    public record RecomputeResult(List<String> affectedSymbols, int affectedNodeCount,
                                  List<String> affectedAcIds, List<String> affectedTestcaseIds,
                                  int staleAggregates) {}
}
