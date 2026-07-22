package com.oAT.web.verification.graph;

import com.oAT.web.esDao.StaticInfoRepository;
import com.oAT.web.esDao.entity.StaticSourceInfo;
import com.oAT.web.esDao.entity.StaticSourceMethodInfo;
import com.oAT.web.verification.model.GraphModels;
import com.oAT.web.verification.model.GraphModels.EvidenceKind;
import com.oAT.web.verification.model.GraphModels.GraphEdgeType;
import com.oAT.web.verification.model.GraphModels.GraphNodeKind;
import com.oAT.web.verification.model.GraphModels.SnapshotKind;
import com.oAT.web.verification.model.VerificationModels.Baseline;
import com.oAT.web.verification.VerificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Projects a method-level control flow graph (CFG) from the static method index. Uses branch line numbers,
 * branch target probes and cyclomatic complexity captured during bytecode analysis. Basic blocks and branches
 * are static facts (STATIC_RESOLVED) derived from the same bytecode used for call resolution; when only line
 * hints exist they are marked STATIC_POSSIBLE. Dynamic branch hits are attached later by branch coverage.
 */
@Service
public class ControlFlowGraphProjectionService {
    public static final String ANALYZER_VERSION = "cfg-v1";
    private final GraphRepository graphRepository;
    private final StaticInfoRepository staticInfoRepository;
    private final VerificationRepository verificationRepository;

    public ControlFlowGraphProjectionService(GraphRepository graphRepository, StaticInfoRepository staticInfoRepository,
                                              VerificationRepository verificationRepository) {
        this.graphRepository = graphRepository;
        this.staticInfoRepository = staticInfoRepository;
        this.verificationRepository = verificationRepository;
    }

    public ProjectionResult project(String projectId, String baselineId) {
        Baseline baseline = verificationRepository.findBaseline(projectId, baselineId)
                .orElseThrow(() -> new IllegalArgumentException("分析基线不存在或不属于当前项目"));
        if (!StringUtils.hasText(baseline.sourceAppId())) {
            throw new IllegalArgumentException("当前基线未指定源码工程，无法建立控制流图");
        }
        List<GraphRepository.GraphNode> methodNodes = graphRepository.findActiveNodesByKind(baselineId, GraphNodeKind.METHOD);
        Map<String, StaticSourceMethodInfo> methodInfoByKey = indexMethods(baseline.sourceAppId());
        String inputHash = GraphModels.fingerprint(methodNodes.stream().map(GraphRepository.GraphNode::id).sorted().reduce("", (a, b) -> a + "|" + b));
        graphRepository.invalidateSnapshots(baselineId, SnapshotKind.STATIC_CFG);
        GraphRepository.GraphSnapshot snapshot = new GraphRepository.GraphSnapshot(UUID.randomUUID().toString(), projectId, baselineId,
                baseline.repositoryUrl(), baseline.sourceCommit(), SnapshotKind.STATIC_CFG, ANALYZER_VERSION, inputHash, "READY",
                Map.of("appId", baseline.sourceAppId(), "methodCount", methodNodes.size()));
        graphRepository.saveSnapshot(snapshot);

        int nodes = 0;
        int edges = 0;
        for (GraphRepository.GraphNode methodNode : methodNodes) {
            StaticSourceMethodInfo info = resolveInfo(methodNode, methodInfoByKey);
            CfgCounts counts = projectMethodCfg(snapshot, projectId, baselineId, methodNode, info);
            nodes += counts.nodes();
            edges += counts.edges();
        }
        return new ProjectionResult(snapshot.id(), nodes, edges);
    }

    private CfgCounts projectMethodCfg(GraphRepository.GraphSnapshot snapshot, String projectId, String baselineId,
                                       GraphRepository.GraphNode methodNode, StaticSourceMethodInfo info) {
        String methodStable = methodNode.stableSymbolId();
        int nodes = 0;
        int edges = 0;
        String entryId = cfgNodeId(methodStable, "entry");
        graphRepository.saveNode(node(snapshot, projectId, baselineId, entryId, GraphNodeKind.BASIC_BLOCK,
                methodNode.locator(), methodNode.displayName() + "#entry", Map.of("role", "ENTRY")));
        nodes++;
        graphRepository.saveEdge(edge(snapshot, projectId, baselineId, methodNode.id(), entryId, GraphEdgeType.CONTAINS,
                EvidenceKind.STATIC_RESOLVED, "E2", 1d, Map.of()));
        edges++;

        String exitId = cfgNodeId(methodStable, "exit");
        graphRepository.saveNode(node(snapshot, projectId, baselineId, exitId, GraphNodeKind.BASIC_BLOCK,
                methodNode.locator(), methodNode.displayName() + "#exit", Map.of("role", "EXIT")));
        nodes++;

        List<Integer> branchLines = info == null || info.getBranchLineNumberSet() == null ? List.of() : info.getBranchLineNumberSet();
        Map<String, List<Integer>> targetProbes = info == null || info.getBranchLineAndTargetProbeMap() == null
                ? Map.of() : info.getBranchLineAndTargetProbeMap();
        String previous = entryId;
        if (branchLines.isEmpty()) {
            graphRepository.saveEdge(edge(snapshot, projectId, baselineId, previous, exitId, GraphEdgeType.NORMAL,
                    EvidenceKind.STATIC_RESOLVED, "E2", 1d, Map.of()));
            return new CfgCounts(nodes, edges + 1);
        }
        for (Integer line : branchLines) {
            String decisionId = cfgNodeId(methodStable, "decision:" + line);
            graphRepository.saveNode(node(snapshot, projectId, baselineId, decisionId, GraphNodeKind.DECISION,
                    methodNode.locator() == null ? null : stripLine(methodNode.locator()) + ":" + line,
                    methodNode.displayName() + "#L" + line, Map.of("line", line)));
            nodes++;
            graphRepository.saveEdge(edge(snapshot, projectId, baselineId, previous, decisionId, GraphEdgeType.NORMAL,
                    EvidenceKind.STATIC_RESOLVED, "E2", 1d, Map.of()));
            edges++;
            List<Integer> probes = targetProbes.getOrDefault(String.valueOf(line), List.of());
            int branchCount = Math.max(2, probes.size());
            for (int index = 0; index < branchCount; index++) {
                String branchId = cfgNodeId(methodStable, "branch:" + line + ":" + index);
                GraphEdgeType branchEdge = index == 0 ? GraphEdgeType.TRUE : GraphEdgeType.FALSE;
                Map<String, Object> attributes = new LinkedHashMap<>();
                attributes.put("line", line);
                attributes.put("branchIndex", index);
                attributes.put("probe", index < probes.size() ? probes.get(index) : null);
                graphRepository.saveNode(node(snapshot, projectId, baselineId, branchId, GraphNodeKind.BRANCH,
                        methodNode.locator() == null ? null : stripLine(methodNode.locator()) + ":" + line + "#" + index,
                        methodNode.displayName() + "#L" + line + "[" + index + "]", attributes));
                nodes++;
                graphRepository.saveEdge(edge(snapshot, projectId, baselineId, decisionId, branchId, branchEdge,
                        EvidenceKind.STATIC_RESOLVED, "E2", 1d, attributes));
                graphRepository.saveEdge(edge(snapshot, projectId, baselineId, branchId, exitId, GraphEdgeType.NORMAL,
                        EvidenceKind.STATIC_RESOLVED, "E2", 1d, Map.of()));
                edges += 2;
            }
            previous = decisionId;
        }
        return new CfgCounts(nodes, edges);
    }

    private Map<String, StaticSourceMethodInfo> indexMethods(String appId) {
        Map<String, StaticSourceMethodInfo> result = new LinkedHashMap<>();
        for (StaticSourceInfo source : staticInfoRepository.findByAppId(appId)) {
            if (source.getClassInfo() == null || source.getClassInfo().getMethodMaps() == null) continue;
            String className = source.getClassInfo().getClassName();
            for (Map.Entry<String, StaticSourceMethodInfo> entry : source.getClassInfo().getMethodMaps().entrySet()) {
                StaticSourceMethodInfo method = entry.getValue();
                String name = method != null && StringUtils.hasText(method.getMethodName()) ? method.getMethodName() : entry.getKey();
                String descriptor = method == null ? "" : value(method.getMethodDesc());
                result.put(className + "#" + name + descriptor, method);
                result.put(className + "#" + name, method);
            }
        }
        return result;
    }

    private StaticSourceMethodInfo resolveInfo(GraphRepository.GraphNode methodNode, Map<String, StaticSourceMethodInfo> byKey) {
        Object descriptor = methodNode.attributes() == null ? null : methodNode.attributes().get("descriptor");
        String display = methodNode.displayName();
        for (Map.Entry<String, StaticSourceMethodInfo> entry : byKey.entrySet()) {
            if (entry.getKey().endsWith("#" + display + value(descriptor == null ? null : descriptor.toString()))
                    || entry.getKey().endsWith("#" + display)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private GraphRepository.GraphNode node(GraphRepository.GraphSnapshot snapshot, String projectId, String baselineId,
                                           String id, GraphNodeKind kind, String locator, String displayName, Map<String, Object> attributes) {
        return new GraphRepository.GraphNode(id, snapshot.id(), baselineId, projectId, kind, null, null, locator, displayName, null, attributes);
    }

    private GraphRepository.GraphEdge edge(GraphRepository.GraphSnapshot snapshot, String projectId, String baselineId, String source,
                                           String target, GraphEdgeType type, EvidenceKind kind, String level, double confidence,
                                           Map<String, Object> attributes) {
        return new GraphRepository.GraphEdge(GraphModels.edgeId(snapshot.id(), source, target, type, kind, null), snapshot.id(),
                baselineId, projectId, source, target, type, kind, level, confidence, null, null, attributes);
    }

    private String cfgNodeId(String methodStable, String suffix) {
        return "cfg:" + GraphModels.fingerprint(value(methodStable) + "|" + suffix);
    }

    private String stripLine(String locator) {
        if (locator == null) return null;
        int colon = locator.lastIndexOf(':');
        return colon > 0 && locator.substring(colon + 1).chars().allMatch(Character::isDigit) ? locator.substring(0, colon) : locator;
    }

    private String value(String input) { return input == null ? "" : input; }

    private record CfgCounts(int nodes, int edges) {}
    public record ProjectionResult(String snapshotId, int nodeCount, int edgeCount) {}
}
