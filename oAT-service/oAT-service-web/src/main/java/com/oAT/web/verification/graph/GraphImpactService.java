package com.oAT.web.verification.graph;

import com.oAT.web.verification.VerificationRepository;
import com.oAT.web.verification.model.GraphModels;
import com.oAT.web.verification.model.VerificationModels.TraceLink;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class GraphImpactService {
    public static final String AGGREGATE_KIND = "SYMBOL_IMPACT";
    private final GraphRepository graphRepository;
    private final VerificationRepository verificationRepository;

    public GraphImpactService(GraphRepository graphRepository, VerificationRepository verificationRepository) {
        this.graphRepository = graphRepository;
        this.verificationRepository = verificationRepository;
    }

    public ImpactResult analyze(String projectId, String baselineId, String symbolId, Integer requestedDepth) {
        verificationRepository.findBaseline(projectId, baselineId)
                .orElseThrow(() -> new IllegalArgumentException("分析基线不存在或不属于当前项目"));
        GraphRepository.GraphNode root = graphRepository.findActiveNodeBySymbol(baselineId, symbolId)
                .orElseThrow(() -> new IllegalArgumentException("未找到活跃代码符号: " + symbolId));
        int depth = requestedDepth == null ? 3 : requestedDepth;
        if (depth < 1 || depth > 6) throw new IllegalArgumentException("depth必须在1到6之间");
        List<GraphRepository.GraphNode> nodes = graphRepository.findNodes(baselineId, root.id(), depth, 1_000);
        List<String> symbols = nodes.stream().map(GraphRepository.GraphNode::stableSymbolId).filter(value -> value != null && !value.isBlank()).toList();
        List<TraceLink> links = verificationRepository.findTraceLinks(baselineId);
        List<TraceLink> affected = links.stream().filter(link -> "SOURCE_SYMBOL".equals(link.targetType())
                && symbols.stream().anyMatch(symbol -> symbol.contains(link.targetId()) || link.targetId().contains(symbol))).toList();
        List<String> acIds = affected.stream().map(TraceLink::sourceId).distinct().toList();
        List<String> testcaseIds = links.stream().filter(link -> acIds.contains(link.sourceId()) && "TESTCASE".equals(link.targetType()))
                .map(TraceLink::targetId).distinct().toList();
        List<String> impactedSymbols = nodes.stream().map(GraphRepository.GraphNode::stableSymbolId).filter(value -> value != null && !value.isBlank()).distinct().toList();
        Map<String, Object> payload = Map.of("rootSymbol", symbolId, "depth", depth, "affectedAcIds", acIds,
                "affectedTestcaseIds", testcaseIds, "affectedSymbols", impactedSymbols, "nodeCount", nodes.size());
        String sourceHash = GraphModels.fingerprint(symbolId + "|" + depth + "|" + impactedSymbols);
        graphRepository.saveAggregate(new GraphRepository.GraphAggregate("impact:" + GraphModels.fingerprint(baselineId + "|" + sourceHash),
                baselineId, projectId, AGGREGATE_KIND, root.id(), sourceHash, payload));
        return new ImpactResult(root.id(), impactedSymbols, acIds, testcaseIds, nodes.size());
    }

    public record ImpactResult(String rootNodeId, List<String> affectedSymbols, List<String> affectedAcIds,
                               List<String> affectedTestcaseIds, int traversedNodeCount) {}
}
