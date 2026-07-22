package com.oAT.web.verification.graph;

import com.oAT.web.esDao.ClassCoverageIndexRepository;
import com.oAT.web.esDao.entity.ClassCoverageIndex;
import com.oAT.web.verification.VerificationRepository;
import com.oAT.web.verification.model.GraphModels;
import com.oAT.web.verification.model.GraphModels.EvidenceKind;
import com.oAT.web.verification.model.GraphModels.GraphEdgeType;
import com.oAT.web.verification.model.GraphModels.GraphNodeKind;
import com.oAT.web.verification.model.GraphModels.SnapshotKind;
import com.oAT.web.verification.model.VerificationModels.Baseline;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Attaches dynamic branch-hit facts to CFG BRANCH nodes. Only branches with real covered branch-target probes
 * from the coverage report are linked with a COVERED edge (evidence E4, scope BRANCH). Branches that the CFG
 * knows about but coverage never hit stay uncovered — they are the raw material for REACHABLE_NOT_EXECUTED.
 */
@Service
public class BranchCoverageProjectionService {
    public static final String ANALYZER_VERSION = "branch-coverage-v1";
    private final GraphRepository graphRepository;
    private final ClassCoverageIndexRepository coverageRepository;
    private final VerificationRepository verificationRepository;

    public BranchCoverageProjectionService(GraphRepository graphRepository, ClassCoverageIndexRepository coverageRepository,
                                           VerificationRepository verificationRepository) {
        this.graphRepository = graphRepository;
        this.coverageRepository = coverageRepository;
        this.verificationRepository = verificationRepository;
    }

    public ProjectionResult project(String projectId, String baselineId) {
        Baseline baseline = verificationRepository.findBaseline(projectId, baselineId)
                .orElseThrow(() -> new IllegalArgumentException("分析基线不存在或不属于当前项目"));
        if (!StringUtils.hasText(baseline.coverageAssetId())) {
            throw new IllegalArgumentException("当前基线没有覆盖率资产");
        }
        List<ClassCoverageIndex> coverage = coverageRepository.findByReportId(baseline.coverageAssetId());
        String inputHash = GraphModels.fingerprint(coverage.stream().map(ClassCoverageIndex::getId).sorted().reduce("", (a, b) -> a + "|" + b) + "|branch");
        graphRepository.invalidateSnapshots(baselineId, SnapshotKind.RUNTIME_BRANCH);
        GraphRepository.GraphSnapshot snapshot = new GraphRepository.GraphSnapshot(UUID.randomUUID().toString(), projectId, baselineId,
                baseline.repositoryUrl(), baseline.sourceCommit(), SnapshotKind.RUNTIME_BRANCH, ANALYZER_VERSION, inputHash, "READY",
                Map.of("reportId", baseline.coverageAssetId(), "scope", "BRANCH"));
        graphRepository.saveSnapshot(snapshot);
        String executionId = UUID.randomUUID().toString();
        graphRepository.saveRuntimeExecution(new GraphRepository.RuntimeExecution(executionId, projectId, baselineId, baseline.sourceCommit(),
                baseline.executionAssetId(), "unknown", ANALYZER_VERSION, null, inputHash, null, null, OffsetDateTime.now(),
                Map.of("coverageAssetId", baseline.coverageAssetId(), "scope", "BRANCH")));

        int nodes = 0;
        int edges = 0;
        for (ClassCoverageIndex file : coverage) {
            if (file.getMethods() == null) continue;
            for (ClassCoverageIndex.MethodCoverageDetail method : file.getMethods()) {
                if (method == null || !StringUtils.hasText(method.getMethodName())) continue;
                Map<String, List<Integer>> coveredProbes = method.getCoveredBranchTargetProbeMap();
                if (coveredProbes == null || coveredProbes.isEmpty()) continue;
                for (Map.Entry<String, List<Integer>> entry : coveredProbes.entrySet()) {
                    int line = parseLine(entry.getKey());
                    if (line <= 0 || entry.getValue() == null) continue;
                    for (int probeIndex = 0; probeIndex < entry.getValue().size(); probeIndex++) {
                        GraphRepository.GraphNode branchNode = graphRepository
                                .findActiveBranchNode(baselineId, null, line, probeIndex).orElse(null);
                        if (branchNode == null) continue;
                        String locator = file.getSourcePath() + ":" + line + "#" + probeIndex;
                        String coverageNodeId = "branch-coverage:" + GraphModels.fingerprint(snapshot.id() + "|" + locator);
                        Map<String, Object> attributes = branchAttributes(method, line, probeIndex);
                        graphRepository.saveNode(new GraphRepository.GraphNode(coverageNodeId, snapshot.id(), baselineId, projectId,
                                GraphNodeKind.COVERAGE_UNIT, null, null, locator, method.getMethodName() + "#branch", null, attributes));
                        graphRepository.saveEdge(new GraphRepository.GraphEdge(
                                GraphModels.edgeId(snapshot.id(), coverageNodeId, branchNode.id(), GraphEdgeType.COVERED, EvidenceKind.COVERAGE, executionId),
                                snapshot.id(), baselineId, projectId, coverageNodeId, branchNode.id(), GraphEdgeType.COVERED, EvidenceKind.COVERAGE,
                                "E4", 1d, executionId, locator, attributes));
                        nodes++;
                        edges++;
                    }
                }
            }
        }
        return new ProjectionResult(snapshot.id(), executionId, nodes, edges);
    }

    private Map<String, Object> branchAttributes(ClassCoverageIndex.MethodCoverageDetail method, int line, int probeIndex) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("scope", "BRANCH");
        attributes.put("line", line);
        attributes.put("branchIndex", probeIndex);
        attributes.put("method", method.getMethodName());
        attributes.put("descriptor", method.getMethodDesc());
        return attributes;
    }

    private int parseLine(String key) {
        if (!StringUtils.hasText(key)) return 0;
        String head = key.contains(":") ? key.substring(0, key.indexOf(':')) : key;
        try {
            return Integer.parseInt(head.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    public record ProjectionResult(String snapshotId, String executionId, int nodeCount, int edgeCount) {}
}
