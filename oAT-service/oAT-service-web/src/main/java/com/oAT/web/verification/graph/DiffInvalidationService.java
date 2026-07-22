package com.oAT.web.verification.graph;

import com.oAT.web.verification.VerificationRepository;
import com.oAT.web.verification.model.GraphModels;
import com.oAT.web.verification.model.GraphModels.SnapshotKind;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Deterministic six-type Diff invalidation matrix from plan section 9. Each change type invalidates only the
 * fact/derivation scope it can affect, following "append new facts, invalidate stale derivations". Prompt/model
 * and rule changes invalidate only AI-derived candidates/conclusions, never raw facts. Analyzer upgrades
 * invalidate the static graph and everything derived from it. Every invocation records an audit aggregate.
 */
@Service
public class DiffInvalidationService {
    public static final String AGGREGATE_KIND = "DIFF_INVALIDATION";
    private final GraphRepository graphRepository;
    private final VerificationRepository verificationRepository;

    public DiffInvalidationService(GraphRepository graphRepository, VerificationRepository verificationRepository) {
        this.graphRepository = graphRepository;
        this.verificationRepository = verificationRepository;
    }

    public enum ChangeType { REQUIREMENT, TESTCASE, CODE, CONFIG_SQL_API, COVERAGE, EXECUTION_TRACE, PROMPT_MODEL_RULE, ANALYZER_UPGRADE }

    public InvalidationResult apply(String projectId, String baselineId, ChangeType changeType) {
        verificationRepository.findBaseline(projectId, baselineId)
                .orElseThrow(() -> new IllegalArgumentException("分析基线不存在或不属于当前项目"));
        if (changeType == null) throw new IllegalArgumentException("变更类型不能为空");

        List<String> invalidatedSnapshots = new ArrayList<>();
        List<String> invalidatedAggregates = new ArrayList<>();
        boolean markBaselineStale = false;

        switch (changeType) {
            case REQUIREMENT -> {
                invalidateSnapshot(baselineId, SnapshotKind.TRACEABILITY, invalidatedSnapshots);
                invalidateAggregate(baselineId, AcceptanceCriterionFusionService.AGGREGATE_KIND, invalidatedAggregates);
                invalidateAggregate(baselineId, AssertionConsistencyService.AGGREGATE_KIND, invalidatedAggregates);
                markBaselineStale = true;
            }
            case TESTCASE -> {
                invalidateSnapshot(baselineId, SnapshotKind.TRACEABILITY, invalidatedSnapshots);
                invalidateAggregate(baselineId, AcceptanceCriterionFusionService.AGGREGATE_KIND, invalidatedAggregates);
                invalidateAggregate(baselineId, AssertionConsistencyService.AGGREGATE_KIND, invalidatedAggregates);
                markBaselineStale = true;
            }
            case CODE -> {
                invalidateSnapshot(baselineId, SnapshotKind.STATIC, invalidatedSnapshots);
                invalidateSnapshot(baselineId, SnapshotKind.STATIC_CFG, invalidatedSnapshots);
                invalidateSnapshot(baselineId, SnapshotKind.STATIC_DEPENDENCY, invalidatedSnapshots);
                invalidateAggregate(baselineId, AcceptanceCriterionFusionService.AGGREGATE_KIND, invalidatedAggregates);
                markBaselineStale = true;
            }
            case CONFIG_SQL_API -> {
                invalidateSnapshot(baselineId, SnapshotKind.STATIC_DEPENDENCY, invalidatedSnapshots);
                invalidateAggregate(baselineId, AcceptanceCriterionFusionService.AGGREGATE_KIND, invalidatedAggregates);
                markBaselineStale = true;
            }
            case COVERAGE -> {
                invalidateSnapshot(baselineId, SnapshotKind.RUNTIME, invalidatedSnapshots);
                invalidateSnapshot(baselineId, SnapshotKind.RUNTIME_BRANCH, invalidatedSnapshots);
                invalidateAggregate(baselineId, AcceptanceCriterionFusionService.AGGREGATE_KIND, invalidatedAggregates);
            }
            case EXECUTION_TRACE -> {
                invalidateSnapshot(baselineId, SnapshotKind.RUNTIME_TRACE, invalidatedSnapshots);
                invalidateAggregate(baselineId, AcceptanceCriterionFusionService.AGGREGATE_KIND, invalidatedAggregates);
            }
            case PROMPT_MODEL_RULE -> {
                // Only AI-derived conclusions are invalidated; raw fact graphs stay valid.
                invalidateAggregate(baselineId, AcceptanceCriterionFusionService.AGGREGATE_KIND, invalidatedAggregates);
                invalidateAggregate(baselineId, AssertionConsistencyService.AGGREGATE_KIND, invalidatedAggregates);
            }
            case ANALYZER_UPGRADE -> {
                invalidateSnapshot(baselineId, SnapshotKind.STATIC, invalidatedSnapshots);
                invalidateSnapshot(baselineId, SnapshotKind.STATIC_CFG, invalidatedSnapshots);
                invalidateSnapshot(baselineId, SnapshotKind.STATIC_DEPENDENCY, invalidatedSnapshots);
                invalidateAggregate(baselineId, AcceptanceCriterionFusionService.AGGREGATE_KIND, invalidatedAggregates);
                invalidateAggregate(baselineId, AssertionConsistencyService.AGGREGATE_KIND, invalidatedAggregates);
                markBaselineStale = true;
            }
        }

        if (markBaselineStale) verificationRepository.markBaselineStale(baselineId);

        String sourceHash = GraphModels.fingerprint(changeType.name() + "|" + invalidatedSnapshots + "|" + invalidatedAggregates);
        Map<String, Object> payload = Map.of(
                "changeType", changeType.name(),
                "invalidatedSnapshotKinds", invalidatedSnapshots,
                "invalidatedAggregateKinds", invalidatedAggregates,
                "baselineMarkedStale", markBaselineStale);
        graphRepository.saveAggregate(new GraphRepository.GraphAggregate(
                "diff-invalidation:" + GraphModels.fingerprint(baselineId + "|" + sourceHash), baselineId, projectId,
                AGGREGATE_KIND, changeType.name(), sourceHash, payload));
        return new InvalidationResult(changeType.name(), invalidatedSnapshots, invalidatedAggregates, markBaselineStale);
    }

    public static final String LINEAGE_KIND = "BASELINE_LINEAGE";

    /**
     * Records baseline succession lineage (predecessor superseded_by successor) as a graph aggregate, and marks
     * the predecessor baseline STALE. Lineage lives as an aggregate to avoid a risky core baseline-table migration.
     */
    public LineageResult recordSuccession(String projectId, String predecessorBaselineId, String successorBaselineId) {
        verificationRepository.findBaseline(projectId, predecessorBaselineId)
                .orElseThrow(() -> new IllegalArgumentException("前序分析基线不存在或不属于当前项目"));
        verificationRepository.findBaseline(projectId, successorBaselineId)
                .orElseThrow(() -> new IllegalArgumentException("后继分析基线不存在或不属于当前项目"));
        verificationRepository.markBaselineStale(predecessorBaselineId);
        String sourceHash = GraphModels.fingerprint(predecessorBaselineId + "->" + successorBaselineId);
        Map<String, Object> payload = Map.of(
                "supersededBaselineId", predecessorBaselineId,
                "supersededByBaselineId", successorBaselineId);
        graphRepository.saveAggregate(new GraphRepository.GraphAggregate(
                "lineage:" + GraphModels.fingerprint(predecessorBaselineId + "|" + sourceHash), predecessorBaselineId, projectId,
                LINEAGE_KIND, successorBaselineId, sourceHash, payload));
        return new LineageResult(predecessorBaselineId, successorBaselineId);
    }

    public List<GraphRepository.GraphAggregate> lineage(String projectId, String baselineId) {
        verificationRepository.findBaseline(projectId, baselineId)
                .orElseThrow(() -> new IllegalArgumentException("分析基线不存在或不属于当前项目"));
        return graphRepository.findAggregates(baselineId, LINEAGE_KIND);
    }

    private void invalidateSnapshot(String baselineId, SnapshotKind kind, List<String> record) {
        graphRepository.invalidateSnapshots(baselineId, kind);
        record.add(kind.name());
    }

    private void invalidateAggregate(String baselineId, String kind, List<String> record) {
        graphRepository.invalidateAggregates(baselineId, kind);
        record.add(kind);
    }

    public record InvalidationResult(String changeType, List<String> invalidatedSnapshotKinds,
                                     List<String> invalidatedAggregateKinds, boolean baselineMarkedStale) {}
    public record LineageResult(String supersededBaselineId, String supersededByBaselineId) {}
}
