package com.oAT.web.verification.graph;

import com.oAT.web.esDao.StaticInfoRepository;
import com.oAT.web.esDao.entity.StaticSourceInfo;
import com.oAT.web.esDao.entity.StaticSourceMethodInfo;
import com.oAT.web.verification.VerificationRepository;
import com.oAT.web.verification.model.GraphModels;
import com.oAT.web.verification.model.GraphModels.EvidenceKind;
import com.oAT.web.verification.model.GraphModels.GraphEdgeType;
import com.oAT.web.verification.model.GraphModels.GraphNodeKind;
import com.oAT.web.verification.model.GraphModels.SnapshotKind;
import com.oAT.web.verification.model.VerificationModels.Baseline;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Projects static type-level dependency edges (IMPORTS) derived from cross-type method invocation owners
 * captured by bytecode analysis. This is a real, bytecode-backed fact. Inheritance edges (EXTENDS/IMPLEMENTS)
 * and injection edges (INJECTS) are only emitted when the underlying index carries that information; when it
 * does not, they are intentionally omitted rather than inferred from names to avoid polluting high-trust facts.
 */
@Service
public class StaticDependencyGraphProjectionService {
    public static final String ANALYZER_VERSION = "static-dependency-v1";
    private final GraphRepository graphRepository;
    private final StaticInfoRepository staticInfoRepository;
    private final VerificationRepository verificationRepository;

    public StaticDependencyGraphProjectionService(GraphRepository graphRepository, StaticInfoRepository staticInfoRepository,
                                                  VerificationRepository verificationRepository) {
        this.graphRepository = graphRepository;
        this.staticInfoRepository = staticInfoRepository;
        this.verificationRepository = verificationRepository;
    }

    public ProjectionResult project(String projectId, String baselineId) {
        Baseline baseline = verificationRepository.findBaseline(projectId, baselineId)
                .orElseThrow(() -> new IllegalArgumentException("分析基线不存在或不属于当前项目"));
        if (!StringUtils.hasText(baseline.sourceAppId())) {
            throw new IllegalArgumentException("当前基线未指定源码工程，无法建立依赖图");
        }
        List<GraphRepository.GraphNode> typeNodes = graphRepository.findActiveNodesByKind(baselineId, GraphNodeKind.TYPE);
        Map<String, String> typeNodeIdByName = new LinkedHashMap<>();
        for (GraphRepository.GraphNode node : typeNodes) typeNodeIdByName.put(node.displayName(), node.id());

        List<StaticSourceInfo> classes = staticInfoRepository.findByAppId(baseline.sourceAppId());
        String inputHash = GraphModels.fingerprint(classes.stream().map(StaticSourceInfo::getId).sorted().reduce("", (a, b) -> a + "|" + b));
        graphRepository.invalidateSnapshots(baselineId, SnapshotKind.STATIC_DEPENDENCY);
        GraphRepository.GraphSnapshot snapshot = new GraphRepository.GraphSnapshot(UUID.randomUUID().toString(), projectId, baselineId,
                baseline.repositoryUrl(), baseline.sourceCommit(), SnapshotKind.STATIC_DEPENDENCY, ANALYZER_VERSION, inputHash, "READY",
                Map.of("appId", baseline.sourceAppId(), "typeCount", typeNodes.size()));
        graphRepository.saveSnapshot(snapshot);

        int edges = 0;
        Set<String> emitted = new LinkedHashSet<>();
        for (StaticSourceInfo source : classes) {
            if (source.getClassInfo() == null || source.getClassInfo().getMethodMaps() == null) continue;
            String owner = source.getClassInfo().getClassName();
            String ownerNodeId = typeNodeIdByName.get(simpleName(owner));
            if (ownerNodeId == null) ownerNodeId = typeNodeIdByName.get(owner);
            if (ownerNodeId == null) continue;
            for (StaticSourceMethodInfo method : source.getClassInfo().getMethodMaps().values()) {
                if (method == null || method.getInvocations() == null) continue;
                for (StaticSourceMethodInfo.InvocationInfo invocation : method.getInvocations()) {
                    if (invocation == null || !StringUtils.hasText(invocation.getOwner())) continue;
                    String targetOwner = invocation.getOwner().replace('/', '.');
                    if (targetOwner.equals(owner)) continue;
                    String targetNodeId = typeNodeIdByName.get(simpleName(targetOwner));
                    if (targetNodeId == null) targetNodeId = typeNodeIdByName.get(targetOwner);
                    if (targetNodeId == null || targetNodeId.equals(ownerNodeId)) continue;
                    String dedupe = ownerNodeId + "->" + targetNodeId;
                    if (!emitted.add(dedupe)) continue;
                    graphRepository.saveEdge(edge(snapshot, projectId, baselineId, ownerNodeId, targetNodeId, GraphEdgeType.IMPORTS,
                            EvidenceKind.STATIC_RESOLVED, "E2", .85d, Map.of("targetType", targetOwner)));
                    edges++;
                }
            }
        }
        return new ProjectionResult(snapshot.id(), typeNodes.size(), edges);
    }

    private GraphRepository.GraphEdge edge(GraphRepository.GraphSnapshot snapshot, String projectId, String baselineId, String source,
                                           String target, GraphEdgeType type, EvidenceKind kind, String level, double confidence,
                                           Map<String, Object> attributes) {
        return new GraphRepository.GraphEdge(GraphModels.edgeId(snapshot.id(), source, target, type, kind, null), snapshot.id(),
                baselineId, projectId, source, target, type, kind, level, confidence, null, null, attributes);
    }

    private String simpleName(String value) {
        if (!StringUtils.hasText(value)) return "";
        String normalized = value.replace('/', '.');
        int dot = normalized.lastIndexOf('.');
        String name = dot >= 0 ? normalized.substring(dot + 1) : normalized;
        int dollar = name.indexOf('$');
        return dollar > 0 ? name.substring(0, dollar) : name;
    }

    public record ProjectionResult(String snapshotId, int typeCount, int edgeCount) {}
}
