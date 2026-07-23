package com.oAT.web.verification.graph;

import com.oAT.web.esDao.ClassCoverageIndexRepository;
import com.oAT.web.esDao.entity.ClassCoverageIndex;
import com.oAT.web.coverage.universal.CoverageReportService;
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

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class RuntimeGraphProjectionService {
    private final GraphRepository graphRepository;
    private final ClassCoverageIndexRepository coverageRepository;
    private final VerificationRepository verificationRepository;
    private final CoverageReportService coverageReportService;
    private final AppService appService;
    private final AssetContentStore assetContentStore;

    public RuntimeGraphProjectionService(GraphRepository graphRepository, ClassCoverageIndexRepository coverageRepository,
                                         VerificationRepository verificationRepository, CoverageReportService coverageReportService,
                                         AppService appService, AssetContentStore assetContentStore) {
        this.graphRepository = graphRepository;
        this.coverageRepository = coverageRepository;
        this.verificationRepository = verificationRepository;
        this.coverageReportService = coverageReportService;
        this.appService = appService;
        this.assetContentStore = assetContentStore;
    }

    public ProjectionResult projectCoverage(String projectId, String baselineId) {
        Baseline baseline = verificationRepository.findBaseline(projectId, baselineId)
                .orElseThrow(() -> new IllegalArgumentException("分析基线不存在或不属于当前项目"));
        if (!StringUtils.hasText(baseline.coverageAssetId())) {
            throw new IllegalArgumentException("当前基线没有覆盖率资产");
        }
        List<ClassCoverageIndex> coverage = coverageRepository.findByReportId(baseline.coverageAssetId());
        if (coverage.isEmpty()) {
            coverage = indexCoverageAsset(projectId, baseline);
        }
        String inputHash = GraphModels.fingerprint(coverage.stream().map(ClassCoverageIndex::getId).sorted().reduce("", (a, b) -> a + "|" + b));
        graphRepository.invalidateSnapshots(baselineId, SnapshotKind.RUNTIME);
        GraphRepository.GraphSnapshot snapshot = new GraphRepository.GraphSnapshot(UUID.randomUUID().toString(), projectId, baselineId,
                baseline.repositoryUrl(), baseline.sourceCommit(), SnapshotKind.RUNTIME, "coverage-projection-v1", inputHash,
                "READY", Map.of("reportId", baseline.coverageAssetId(), "fileCount", coverage.size()));
        graphRepository.saveSnapshot(snapshot);
        String executionId = UUID.randomUUID().toString();
        graphRepository.saveRuntimeExecution(new GraphRepository.RuntimeExecution(executionId, projectId, baselineId, baseline.sourceCommit(),
                baseline.executionAssetId(), "unknown", "coverage-projection-v1", null, inputHash, null, null, OffsetDateTime.now(),
                Map.of("coverageAssetId", baseline.coverageAssetId())));
        int nodes = 0;
        int edges = 0;
        for (ClassCoverageIndex file : coverage) {
            String path = normalizePath(file.getSourcePath());
            GraphRepository.GraphNode target = graphRepository.findActiveNodeByLocator(baselineId, path).orElse(null);
            String coverageNodeId = "coverage:" + GraphModels.fingerprint(snapshot.id() + "|" + path);
            GraphRepository.GraphNode coverageNode = new GraphRepository.GraphNode(coverageNodeId, snapshot.id(), baselineId, projectId,
                    GraphNodeKind.COVERAGE_UNIT, null, null, path + "#coverage", file.getDisplayName(), null, coverageAttributes(file));
            graphRepository.saveNode(coverageNode);
            nodes++;
            if (target != null && !target.id().equals(coverageNode.id())) {
                GraphRepository.GraphEdge edge = new GraphRepository.GraphEdge(
                        GraphModels.edgeId(snapshot.id(), coverageNode.id(), target.id(), GraphEdgeType.COVERED, EvidenceKind.COVERAGE, executionId),
                        snapshot.id(), baselineId, projectId, coverageNode.id(), target.id(), GraphEdgeType.COVERED, EvidenceKind.COVERAGE,
                        "E3", coverageRate(file), executionId, path, coverageAttributes(file));
                graphRepository.saveEdge(edge);
                edges++;
            }
            ProjectionCounts methodCounts = projectMethodCoverage(snapshot, projectId, baselineId, executionId, file, path);
            nodes += methodCounts.nodes();
            edges += methodCounts.edges();
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
        List<ClassCoverageIndex> parsed = coverageReportService.parse(app, value(content).getBytes(java.nio.charset.StandardCharsets.UTF_8)).stream()
                .map(file -> file.toClassCoverageIndex(baseline.sourceAppId()))
                .toList();
        coverageRepository.replaceReport(asset.id(), parsed);
        return parsed;
    }

    private ProjectionCounts projectMethodCoverage(GraphRepository.GraphSnapshot snapshot, String projectId, String baselineId,
                                                   String executionId, ClassCoverageIndex file, String path) {
        if (file.getMethods() == null || file.getMethods().isEmpty()) return ProjectionCounts.empty();
        int nodes = 0;
        int edges = 0;
        for (ClassCoverageIndex.MethodCoverageDetail method : file.getMethods()) {
            if (method == null || (!method.isCovered() && method.getCoveredLines() <= 0)
                    || !StringUtils.hasText(method.getMethodName())) continue;
            String className = StringUtils.hasText(method.getClassName()) ? method.getClassName() : file.getClassName();
            GraphRepository.GraphNode target = graphRepository.findActiveMethod(baselineId, className, method.getMethodName(),
                    method.getMethodDesc(), method.getStartLine()).orElse(null);
            if (target == null) continue;
            String locator = path + ":" + method.getStartLine() + "#" + method.getMethodName();
            String id = "method-coverage:" + GraphModels.fingerprint(snapshot.id() + "|" + locator + "|" + value(method.getMethodDesc()));
            GraphRepository.GraphNode coverage = new GraphRepository.GraphNode(id, snapshot.id(), baselineId, projectId,
                    GraphNodeKind.COVERAGE_UNIT, null, null, locator, method.getMethodName(), null, methodCoverageAttributes(method));
            graphRepository.saveNode(coverage);
            GraphRepository.GraphEdge edge = new GraphRepository.GraphEdge(
                    GraphModels.edgeId(snapshot.id(), coverage.id(), target.id(), GraphEdgeType.COVERED, EvidenceKind.COVERAGE, executionId),
                    snapshot.id(), baselineId, projectId, coverage.id(), target.id(), GraphEdgeType.COVERED, EvidenceKind.COVERAGE,
                    "E4", methodCoverageRate(method), executionId, locator, methodCoverageAttributes(method));
            graphRepository.saveEdge(edge);
            nodes++;
            edges++;
        }
        return new ProjectionCounts(nodes, edges);
    }

    private Map<String, Object> methodCoverageAttributes(ClassCoverageIndex.MethodCoverageDetail method) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", "METHOD");
        result.put("descriptor", value(method.getMethodDesc()));
        result.put("startLine", method.getStartLine());
        result.put("totalLines", method.getTotalLines());
        result.put("coveredLines", method.getCoveredLines());
        result.put("totalBranches", method.getTotalBranchTargets());
        result.put("coveredBranches", method.getCoveredBranchTargets());
        result.put("covered", method.isCovered());
        result.put("lineRate", method.getTotalLines() == 0 ? 0d : (double) method.getCoveredLines() / method.getTotalLines());
        result.put("branchRate", method.getBranchRate());
        return result;
    }

    private double methodCoverageRate(ClassCoverageIndex.MethodCoverageDetail method) {
        if (method.getTotalLines() > 0) return (double) method.getCoveredLines() / method.getTotalLines();
        return method.isCovered() ? 1d : 0d;
    }
    private Map<String, Object> coverageAttributes(ClassCoverageIndex value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalLines", value.getTotalLines());
        result.put("coveredLines", value.getCoveredLines());
        result.put("totalBranches", value.getTotalBranchTargets());
        result.put("coveredBranches", value.getCoveredBranchTargets());
        result.put("totalMethods", value.getTotalMethods());
        result.put("coveredMethods", value.getCoveredMethods());
        result.put("lineRate", value.getLineRate());
        result.put("branchRate", value.getBranchRate());
        return result;
    }

    private double coverageRate(ClassCoverageIndex value) {
        if (value.getLineRate() != null) return value.getLineRate();
        return value.getTotalLines() <= 0 ? 0d : (double) value.getCoveredLines() / value.getTotalLines();
    }

    private String normalizePath(String value) { return value == null ? "unknown" : value.replace('\\', '/'); }
    private String value(String value) { return value == null ? "" : value; }
    private record ProjectionCounts(int nodes, int edges) { static ProjectionCounts empty() { return new ProjectionCounts(0, 0); } }
    public record ProjectionResult(String snapshotId, String executionId, int nodeCount, int edgeCount) {}
}
