package com.oAT.web.verification.graph;

import com.oAT.web.verification.VerificationRepository;
import com.oAT.web.verification.model.GraphModels.FusionState;
import com.oAT.web.verification.model.GraphModels.GraphNodeKind;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
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

    public FusionViewService(GraphRepository graphRepository, VerificationRepository verificationRepository) {
        this.graphRepository = graphRepository;
        this.verificationRepository = verificationRepository;
    }

    public FusionView build(String projectId, String baselineId, int maxNodes) {
        verificationRepository.findBaseline(projectId, baselineId)
                .orElseThrow(() -> new IllegalArgumentException("分析基线不存在或不属于当前项目"));
        List<GraphRepository.GraphNode> methods = graphRepository.findActiveNodesByKind(baselineId, GraphNodeKind.METHOD);
        Set<String> executed = graphRepository.findDynamicallyEvidencedNodeIds(baselineId);
        Set<String> reachable = graphRepository.findStaticallyReachableNodeIds(baselineId);

        List<FusionNode> nodes = new ArrayList<>();
        int executedConfirmed = 0;
        int reachableNotExecuted = 0;
        int notObservable = 0;
        int limit = maxNodes <= 0 ? 1_000 : Math.min(maxNodes, 5_000);
        for (GraphRepository.GraphNode method : methods) {
            FusionState state;
            if (executed.contains(method.id())) {
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

    public record FusionNode(String nodeId, String stableSymbolId, String displayName, String locator, String fusionState) {}

    public record FusionView(List<FusionNode> nodes, int totalMethods, int executedConfirmed, int reachableNotExecuted,
                             int notObservable, boolean clipped, String clipReason) {}
}
