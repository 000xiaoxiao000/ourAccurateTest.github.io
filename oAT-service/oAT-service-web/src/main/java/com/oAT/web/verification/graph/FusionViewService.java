package com.oAT.web.verification.graph;

import com.oAT.web.esDao.ClassCoverageIndexRepository;
import com.oAT.web.esDao.entity.ClassCoverageIndex;
import com.oAT.web.verification.VerificationRepository;
import com.oAT.web.verification.model.GraphModels.FusionState;
import com.oAT.web.verification.model.GraphModels.GraphNodeKind;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Derives the static/dynamic fusion view without duplicating storage. For every static METHOD node it computes
 * a tri-state by joining static reachability against dynamic evidence:
 *  - EXECUTED_CONFIRMED: dynamic evidence (coverage / runtime call / touched) proves execution
 *  - REACHABLE_NOT_EXECUTED: statically reachable but no dynamic evidence in this baseline
 *  - NOT_OBSERVABLE: neither statically reachable nor dynamically evidenced (e.g. dead or unobservable)
 */
@Service
public class FusionViewService {
    private final GraphRepository graphRepository;
    private final VerificationRepository verificationRepository;
    private final ClassCoverageIndexRepository coverageRepository;

    public FusionViewService(GraphRepository graphRepository, VerificationRepository verificationRepository,
                             ClassCoverageIndexRepository coverageRepository) {
        this.graphRepository = graphRepository;
        this.verificationRepository = verificationRepository;
        this.coverageRepository = coverageRepository;
    }

    public FusionView build(String projectId, String baselineId, int maxNodes) {
        verificationRepository.findBaseline(projectId, baselineId)
                .orElseThrow(() -> new IllegalArgumentException("分析基线不存在或不属于当前项目"));
        List<GraphRepository.GraphNode> methods = graphRepository.findActiveNodesByKind(baselineId, GraphNodeKind.METHOD);
        Set<String> executed = new HashSet<>(graphRepository.findDynamicallyEvidencedNodeIds(baselineId));
        Set<String> coveredMethodKeys = findCoveredMethodKeys(projectId, baselineId);
        Set<String> reachable = graphRepository.findStaticallyReachableNodeIds(baselineId);

        List<FusionNode> nodes = new ArrayList<>();
        int executedConfirmed = 0;
        int reachableNotExecuted = 0;
        int notObservable = 0;
        int limit = maxNodes <= 0 ? 1_000 : Math.min(maxNodes, 5_000);
        for (GraphRepository.GraphNode method : methods) {
            FusionState state;
            if (executed.contains(method.id()) || coveredByAsset(method, coveredMethodKeys)) {
                state = FusionState.EXECUTED_CONFIRMED;
                executedConfirmed++;
            } else if (reachable.contains(method.id())) {
                state = FusionState.REACHABLE_NOT_EXECUTED;
                reachableNotExecuted++;
            } else {
                state = FusionState.NOT_OBSERVABLE;
                notObservable++;
            }
            if (nodes.size() < limit) {
                nodes.add(new FusionNode(method.id(), method.stableSymbolId(), method.displayName(), method.locator(), state.name()));
            }
        }
        boolean clipped = methods.size() > limit;
        return new FusionView(nodes, methods.size(), executedConfirmed, reachableNotExecuted, notObservable, clipped,
                clipped ? "融合节点超过上限 " + limit + " 已裁剪，请按聚焦符号或缩小范围再查询" : null);
    }

    /**
     * The coverage tab reads the baseline coverage asset directly. Include the
     * same covered methods here even when the graph projection has not been run.
     */
    private Set<String> findCoveredMethodKeys(String projectId, String baselineId) {
        if (coverageRepository == null) return Set.of();
        var baseline = verificationRepository.findBaseline(projectId, baselineId).orElse(null);
        if (baseline == null || baseline.coverageAssetId() == null || baseline.coverageAssetId().isBlank()) {
            return Set.of();
        }
        Set<String> keys = new HashSet<>();
        for (ClassCoverageIndex file : coverageRepository.findByReportId(baseline.coverageAssetId())) {
            if (file.getMethods() == null) continue;
            for (ClassCoverageIndex.MethodCoverageDetail method : file.getMethods()) {
                if ((!method.isCovered() && method.getCoveredLines() <= 0)
                        || method.getMethodName() == null || method.getMethodName().isBlank()) continue;
                String className = value(method.getClassName(), file.getClassName());
                keys.add(methodKey(className + "#" + method.getMethodName()));
            }
        }
        return keys;
    }

    private boolean coveredByAsset(GraphRepository.GraphNode method, Set<String> coveredKeys) {
        return coveredKeys.contains(methodKey(method.stableSymbolId()))
                || coveredKeys.contains(methodKey(method.logicalSymbolId()));
    }

    /**
     * Coverage reports identify methods as {@code owner#method}, while graph symbol IDs additionally
     * include repository, revision, source path and signature. Joining on this canonical key keeps
     * the fusion view aligned with the coverage tab across source revisions and symbol formats.
     */
    private String methodKey(String value) {
        String normalized = normalize(value);
        int hash = normalized.lastIndexOf('#');
        if (hash < 0) return normalized;
        int methodEnd = normalized.indexOf('(', hash);
        if (methodEnd < 0) methodEnd = normalized.indexOf(':', hash);
        if (methodEnd < 0) methodEnd = normalized.length();
        String owner = normalized.substring(0, hash);
        int symbolSeparator = owner.lastIndexOf(':');
        if (symbolSeparator >= 0) owner = owner.substring(symbolSeparator + 1);
        return owner + normalized.substring(hash, methodEnd);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replace(" ", "");
    }

    private String value(String first, String fallback) {
        return first == null || first.isBlank() ? (fallback == null ? "" : fallback) : first;
    }

    public record FusionNode(String nodeId, String stableSymbolId, String displayName, String locator, String fusionState) {}

    public record FusionView(List<FusionNode> nodes, int totalMethods, int executedConfirmed, int reachableNotExecuted,
                             int notObservable, boolean clipped, String clipReason) {}
}
