package com.oAT.web.verification.graph;

import com.oAT.web.coverage.universal.CoverageReportService;
import com.oAT.web.persistence.ClassCoverageIndexRepository;
import com.oAT.web.persistence.entity.ClassCoverageIndex;
import com.oAT.web.service.AppService;
import com.oAT.web.service.entity.AppVo;
import com.oAT.web.verification.VerificationRepository;
import com.oAT.web.verification.model.GraphModels;
import com.oAT.web.verification.model.GraphModels.EvidenceKind;
import com.oAT.web.verification.model.GraphModels.GraphEdgeType;
import com.oAT.web.verification.model.GraphModels.GraphNodeKind;
import com.oAT.web.verification.model.GraphModels.SnapshotKind;
import com.oAT.web.verification.model.VerificationModels.AssetSnapshot;
import com.oAT.web.verification.model.VerificationModels.Baseline;
import com.oAT.web.verification.storage.AssetContentStore;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final CoverageReportService coverageReportService;
    private final AppService appService;
    private final AssetContentStore assetContentStore;

    public BranchCoverageProjectionService(GraphRepository graphRepository, ClassCoverageIndexRepository coverageRepository,
                                           VerificationRepository verificationRepository, CoverageReportService coverageReportService,
                                           AppService appService, AssetContentStore assetContentStore) {
        this.graphRepository = graphRepository;
        this.coverageRepository = coverageRepository;
        this.verificationRepository = verificationRepository;
        this.coverageReportService = coverageReportService;
        this.appService = appService;
        this.assetContentStore = assetContentStore;
    }

    public ProjectionResult project(String projectId, String baselineId) {
        Baseline baseline = verificationRepository.findBaseline(projectId, baselineId)
                .orElseThrow(() -> new IllegalArgumentException("分析基线不存在或不属于当前项目"));
        if (!StringUtils.hasText(baseline.coverageAssetId())) {
            throw new IllegalArgumentException("当前基线没有覆盖率资产");
        }
        List<ClassCoverageIndex> coverage = coverageRepository.findByReportId(baseline.coverageAssetId());
        if (coverage.isEmpty()) {
            coverage = indexCoverageAsset(projectId, baseline);
        }
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
                Map<String, List<Integer>> totalProbes = method.getTotalBranchTargetProbeMap();
                Map<String, List<Integer>> coveredProbes = method.getCoveredBranchTargetProbeMap();
                Map<String, List<Integer>> branchProbes = totalProbes == null || totalProbes.isEmpty() ? coveredProbes : totalProbes;
                if (branchProbes == null || branchProbes.isEmpty()) continue;
                for (Map.Entry<String, List<Integer>> entry : branchProbes.entrySet()) {
                    int line = parseLine(entry.getKey());
                    if (line <= 0 || entry.getValue() == null) continue;
                    Set<Integer> covered = new LinkedHashSet<>(coveredProbes == null ? List.of() : coveredProbes.getOrDefault(entry.getKey(), List.of()));
                    boolean projectedFromCoveredOnly = totalProbes == null || totalProbes.isEmpty();
                    for (int probeIndex = 0; probeIndex < entry.getValue().size(); probeIndex++) {
                        GraphRepository.GraphNode branchNode = findOrCreateBranchNode(snapshot, projectId, baselineId, file, method, line, probeIndex,
                                entry.getValue().get(probeIndex));
                        if (branchNode.snapshotId().equals(snapshot.id())) nodes++;
                        if (!projectedFromCoveredOnly && !covered.contains(entry.getValue().get(probeIndex))) continue;
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

    private List<ClassCoverageIndex> indexCoverageAsset(String projectId, Baseline baseline) {
        if (!StringUtils.hasText(baseline.sourceAppId())) {
            throw new IllegalArgumentException("当前基线未绑定源码工程，无法解析覆盖率资产");
        }
        AssetSnapshot asset = verificationRepository.findAsset(projectId, baseline.coverageAssetId())
                .orElseThrow(() -> new IllegalArgumentException("当前基线的覆盖率资产不存在"));
        AppVo app = appService.getApp(baseline.sourceAppId());
        if (app == null) throw new IllegalArgumentException("当前基线绑定的源码工程不存在");
        String content = StringUtils.hasText(asset.content()) ? asset.content() : assetContentStore.load(asset.storageKey());
        List<ClassCoverageIndex> parsed = coverageReportService.parse(app, value(content).getBytes(StandardCharsets.UTF_8)).stream()
                .map(file -> file.toClassCoverageIndex(baseline.sourceAppId()))
                .toList();
        coverageRepository.replaceReport(asset.id(), parsed);
        return parsed;
    }

    private GraphRepository.GraphNode findOrCreateBranchNode(GraphRepository.GraphSnapshot snapshot, String projectId, String baselineId,
                                                             ClassCoverageIndex file, ClassCoverageIndex.MethodCoverageDetail method,
                                                             int line, int branchIndex, Integer probe) {
        String path = normalizePath(file.getSourcePath());
        String locator = path + ":" + line + "#" + branchIndex;
        GraphRepository.GraphNode branchNode = graphRepository.findActiveNodeByLocator(baselineId, locator).orElse(null);
        if (branchNode != null && branchNode.kind() != GraphNodeKind.BRANCH) branchNode = null;
        if (branchNode == null) {
            branchNode = graphRepository.findActiveBranchNode(baselineId, null, line, branchIndex).orElse(null);
        }
        if (branchNode != null) return branchNode;
        String className = StringUtils.hasText(method.getClassName()) ? method.getClassName() : file.getClassName();
        String display = firstText(className, path) + "#" + method.getMethodName() + "#L" + line + "[" + branchIndex + "]";
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("line", line);
        attributes.put("branchIndex", branchIndex);
        attributes.put("probe", probe);
        attributes.put("method", method.getMethodName());
        attributes.put("descriptor", value(method.getMethodDesc()));
        attributes.put("source", "coverage");
        GraphModels.SymbolIdentity symbol = new GraphModels.SymbolIdentity(firstText(snapshot.repositoryUrl(), "coverage"),
                firstText(snapshot.sourceCommit(), "unversioned"), path, firstText(className, path),
                method.getMethodName(), value(method.getMethodDesc()), "branch:" + line + ":" + branchIndex);
        GraphRepository.GraphNode node = new GraphRepository.GraphNode("branch:" + symbol.fingerprint(), snapshot.id(), baselineId, projectId,
                GraphNodeKind.BRANCH, symbol.stableId(), symbol.logicalId(), locator, display, null, attributes);
        graphRepository.saveNode(node);
        return node;
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

    private String normalizePath(String value) { return value == null ? "unknown" : value.replace('\\', '/'); }
    private String firstText(String first, String fallback) { return StringUtils.hasText(first) ? first : fallback; }
    private String value(String value) { return value == null ? "" : value; }

    public record ProjectionResult(String snapshotId, String executionId, int nodeCount, int edgeCount) {}
}
