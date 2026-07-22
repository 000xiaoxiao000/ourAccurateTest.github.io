package com.oAT.web.verification.graph;

import com.oAT.web.verification.VerificationRepository;
import com.oAT.web.verification.model.GraphModels.SnapshotKind;
import com.oAT.web.verification.model.VerificationModels.Baseline;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class GraphService {
    private static final int DEFAULT_DEPTH = 3;
    private static final int MAX_DEPTH = 6;
    private static final int DEFAULT_NODES = 200;
    private static final int MAX_NODES = 1_000;
    private static final int DEFAULT_EDGES = 500;
    private static final int MAX_EDGES = 2_000;

    private final GraphRepository graphRepository;
    private final StaticGraphProjectionService staticProjectionService;
    private final PolyglotStaticGraphProjectionService polyglotStaticProjectionService;
    private final RuntimeGraphProjectionService runtimeProjectionService;
    private final RuntimeTraceProjectionService runtimeTraceProjectionService;
    private final TraceabilityGraphProjectionService traceabilityProjectionService;
    private final AcceptanceCriterionFusionService fusionService;
    private final GraphImpactService impactService;
    private final GitChangeGraphInvalidationService gitChangeInvalidationService;
    private final ControlFlowGraphProjectionService cfgProjectionService;
    private final StaticDependencyGraphProjectionService dependencyProjectionService;
    private final BranchCoverageProjectionService branchCoverageProjectionService;
    private final TestExecutionProjectionService testExecutionProjectionService;
    private final FusionViewService fusionViewService;
    private final AssertionConsistencyService assertionConsistencyService;
    private final IncrementalRecomputeService incrementalRecomputeService;
    private final DiffInvalidationService diffInvalidationService;
    private final ReadModelService readModelService;
    private final BaselineComparisonService baselineComparisonService;
    private final VerificationRepository verificationRepository;

    public GraphService(GraphRepository graphRepository, StaticGraphProjectionService staticProjectionService,
                        PolyglotStaticGraphProjectionService polyglotStaticProjectionService,
                        RuntimeGraphProjectionService runtimeProjectionService,
                        RuntimeTraceProjectionService runtimeTraceProjectionService,
                        TraceabilityGraphProjectionService traceabilityProjectionService,
                        AcceptanceCriterionFusionService fusionService,
                        GraphImpactService impactService,
                        GitChangeGraphInvalidationService gitChangeInvalidationService,
                        ControlFlowGraphProjectionService cfgProjectionService,
                        StaticDependencyGraphProjectionService dependencyProjectionService,
                        BranchCoverageProjectionService branchCoverageProjectionService,
                        TestExecutionProjectionService testExecutionProjectionService,
                        FusionViewService fusionViewService,
                        AssertionConsistencyService assertionConsistencyService,
                        IncrementalRecomputeService incrementalRecomputeService,
                        DiffInvalidationService diffInvalidationService,
                        ReadModelService readModelService,
                        BaselineComparisonService baselineComparisonService,
                        VerificationRepository verificationRepository) {
        this.graphRepository = graphRepository;
        this.staticProjectionService = staticProjectionService;
        this.polyglotStaticProjectionService = polyglotStaticProjectionService;
        this.runtimeProjectionService = runtimeProjectionService;
        this.runtimeTraceProjectionService = runtimeTraceProjectionService;
        this.traceabilityProjectionService = traceabilityProjectionService;
        this.fusionService = fusionService;
        this.impactService = impactService;
        this.gitChangeInvalidationService = gitChangeInvalidationService;
        this.cfgProjectionService = cfgProjectionService;
        this.dependencyProjectionService = dependencyProjectionService;
        this.branchCoverageProjectionService = branchCoverageProjectionService;
        this.testExecutionProjectionService = testExecutionProjectionService;
        this.fusionViewService = fusionViewService;
        this.assertionConsistencyService = assertionConsistencyService;
        this.incrementalRecomputeService = incrementalRecomputeService;
        this.diffInvalidationService = diffInvalidationService;
        this.readModelService = readModelService;
        this.baselineComparisonService = baselineComparisonService;
        this.verificationRepository = verificationRepository;
    }

    public void invalidate(String projectId, String baselineId) {
        verificationRepository.findBaseline(projectId, baselineId)
                .orElseThrow(() -> new IllegalArgumentException("分析基线不存在或不属于当前项目"));
        graphRepository.invalidateAll(baselineId);
    }

    public StaticGraphProjectionService.ProjectionResult projectStatic(String projectId, String baselineId) {
        Baseline baseline = verificationRepository.findBaseline(projectId, baselineId)
                .orElseThrow(() -> new IllegalArgumentException("分析基线不存在或不属于当前项目"));
        if (!StringUtils.hasText(baseline.sourceAppId())) {
            throw new IllegalArgumentException("当前基线未指定源码工程，无法建立静态图");
        }
        return staticProjectionService.project(projectId, baseline.id(), baseline.sourceAppId(), baseline.repositoryUrl(), baseline.sourceCommit());
    }

    public StaticGraphProjectionService.ProjectionResult projectPolyglotStatic(String projectId, String baselineId,
                                                                               String language, String sourceContent) {
        Baseline baseline = verificationRepository.findBaseline(projectId, baselineId)
                .orElseThrow(() -> new IllegalArgumentException("分析基线不存在或不属于当前项目"));
        if (!StringUtils.hasText(baseline.sourceAppId())) {
            throw new IllegalArgumentException("当前基线未指定源码工程，无法建立静态图");
        }
        return polyglotStaticProjectionService.project(projectId, baseline.id(), baseline.sourceAppId(),
                baseline.repositoryUrl(), baseline.sourceCommit(), language, sourceContent);
    }

    public RuntimeGraphProjectionService.ProjectionResult projectRuntimeCoverage(String projectId, String baselineId) {
        RuntimeGraphProjectionService.ProjectionResult result = runtimeProjectionService.projectCoverage(projectId, baselineId);
        fusionService.rebuild(projectId, baselineId);
        return result;
    }

    public ControlFlowGraphProjectionService.ProjectionResult projectControlFlow(String projectId, String baselineId) {
        return cfgProjectionService.project(projectId, baselineId);
    }

    public StaticDependencyGraphProjectionService.ProjectionResult projectStaticDependency(String projectId, String baselineId) {
        return dependencyProjectionService.project(projectId, baselineId);
    }

    public BranchCoverageProjectionService.ProjectionResult projectBranchCoverage(String projectId, String baselineId) {
        BranchCoverageProjectionService.ProjectionResult result = branchCoverageProjectionService.project(projectId, baselineId);
        fusionService.rebuild(projectId, baselineId);
        return result;
    }

    public TestExecutionProjectionService.ProjectionResult projectTestExecutions(String projectId, String baselineId) {
        return testExecutionProjectionService.project(projectId, baselineId);
    }

    public FusionViewService.FusionView fusionView(String projectId, String baselineId, Integer maxNodes) {
        return fusionViewService.build(projectId, baselineId, maxNodes == null ? 1_000 : maxNodes);
    }

    public List<AssertionConsistencyService.ConsistencyResult> evaluateAssertionConsistency(String projectId, String baselineId) {
        return assertionConsistencyService.evaluate(projectId, baselineId);
    }

    public List<GraphRepository.GraphAggregate> assertionConsistency(String projectId, String baselineId) {
        return assertionConsistencyService.results(projectId, baselineId);
    }

    public IncrementalRecomputeService.RecomputeResult incrementalRecompute(String projectId, String baselineId, List<String> changedSymbols) {
        return incrementalRecomputeService.recompute(projectId, baselineId, changedSymbols);
    }

    public DiffInvalidationService.InvalidationResult applyDiffInvalidation(String projectId, String baselineId, DiffInvalidationService.ChangeType changeType) {
        return diffInvalidationService.apply(projectId, baselineId, changeType);
    }

    public DiffInvalidationService.LineageResult recordBaselineSuccession(String projectId, String predecessorBaselineId, String successorBaselineId) {
        return diffInvalidationService.recordSuccession(projectId, predecessorBaselineId, successorBaselineId);
    }

    public List<GraphRepository.GraphAggregate> baselineLineage(String projectId, String baselineId) {
        return diffInvalidationService.lineage(projectId, baselineId);
    }

    public ReadModelService.RebuildResult rebuildReadModels(String projectId, String baselineId) {
        return readModelService.rebuildAll(projectId, baselineId);
    }

    public List<GraphRepository.GraphAggregate> readModel(String projectId, String baselineId, String kind) {
        return readModelService.read(projectId, baselineId, kind);
    }

    public BaselineComparisonService.ComparisonResult compareBaselines(String projectId, String baseBaselineId, String targetBaselineId) {
        return baselineComparisonService.compare(projectId, baseBaselineId, targetBaselineId);
    }

    public RuntimeTraceProjectionService.ProjectionResult projectRuntimeTrace(String projectId, String baselineId,
                                                                               RuntimeTraceProjectionService.RuntimeTraceBatch batch) {
        RuntimeTraceProjectionService.ProjectionResult result = runtimeTraceProjectionService.project(projectId, baselineId, batch);
        fusionService.rebuild(projectId, baselineId);
        return result;
    }

    public TraceabilityGraphProjectionService.ProjectionResult projectTraceability(String projectId, String baselineId) {
        verificationRepository.findBaseline(projectId, baselineId)
                .orElseThrow(() -> new IllegalArgumentException("分析基线不存在或不属于当前项目"));
        return traceabilityProjectionService.project(projectId, baselineId);
    }

    public List<AcceptanceCriterionFusionService.FusionResult> rebuildAcceptanceFusion(String projectId, String baselineId) {
        verificationRepository.findBaseline(projectId, baselineId)
                .orElseThrow(() -> new IllegalArgumentException("分析基线不存在或不属于当前项目"));
        return fusionService.rebuild(projectId, baselineId);
    }

    public List<GraphRepository.GraphAggregate> acceptanceFusion(String projectId, String baselineId) {
        return fusionService.results(projectId, baselineId);
    }

    public GraphImpactService.ImpactResult impact(String projectId, String baselineId, String symbolId, Integer depth) {
        return impactService.analyze(projectId, baselineId, symbolId, depth);
    }

    public GitChangeGraphInvalidationService.InvalidationResult applyGitChangeImpact(String projectId, String baselineId,
                                                                                      com.oAT.web.verification.impact.ImpactModels.ImpactReport report,
                                                                                      com.oAT.web.verification.impact.ImpactTraceabilityMapper.TraceabilityImpact traceability) {
        return gitChangeInvalidationService.apply(projectId, baselineId, report, traceability);
    }

    public List<GraphRepository.GraphAggregate> gitChangeImpactHistory(String projectId, String baselineId) {
        return gitChangeInvalidationService.history(projectId, baselineId);
    }

    public GraphView query(String projectId, String baselineId, String focusId, Integer requestedDepth,
                           Integer requestedNodes, Integer requestedEdges) {
        verificationRepository.findBaseline(projectId, baselineId)
                .orElseThrow(() -> new IllegalArgumentException("分析基线不存在或不属于当前项目"));
        int depth = bounded(requestedDepth, DEFAULT_DEPTH, MAX_DEPTH);
        int maxNodes = bounded(requestedNodes, DEFAULT_NODES, MAX_NODES);
        int maxEdges = bounded(requestedEdges, DEFAULT_EDGES, MAX_EDGES);
        List<GraphRepository.GraphNode> nodes = graphRepository.findNodes(baselineId, focusId, depth, maxNodes);
        List<String> nodeIds = nodes.stream().map(GraphRepository.GraphNode::id).toList();
        List<GraphRepository.GraphEdge> edges = graphRepository.findEdges(baselineId, nodeIds, maxEdges);
        GraphSummary summary = summary(baselineId);
        boolean nodesClipped = nodes.size() == maxNodes;
        boolean edgesClipped = edges.size() == maxEdges;
        int totalNodes = focusId == null || focusId.isBlank() ? graphRepository.countActiveNodes(baselineId) : nodes.size();
        int totalEdgesAmong = graphRepository.countActiveEdgesAmong(baselineId, nodeIds);
        List<String> clipReasons = new java.util.ArrayList<>();
        if (nodesClipped) clipReasons.add("节点数达到上限 " + maxNodes + (totalNodes > maxNodes ? "，共约 " + totalNodes + " 个活跃节点被裁剪" : ""));
        if (edgesClipped) clipReasons.add("边数达到上限 " + maxEdges + (totalEdgesAmong > maxEdges ? "，当前范围内共约 " + totalEdgesAmong + " 条边被裁剪" : ""));
        String expandHint = (nodesClipped || edgesClipped)
                ? "请通过 focusId 聚焦具体符号、减小 depth，或提高 maxNodes/maxEdges（上限 " + MAX_NODES + "/" + MAX_EDGES + "）以展开更多边界"
                : null;
        return new GraphView(graphRepository.findActiveSnapshot(baselineId, SnapshotKind.STATIC).orElse(null), nodes, edges,
                nodesClipped, edgesClipped, depth, maxNodes, maxEdges, summary, clipReasons, totalNodes, totalEdgesAmong, expandHint);
    }

    private GraphSummary summary(String baselineId) {
        List<GraphRepository.GraphSnapshot> snapshots = graphRepository.findActiveSnapshots(baselineId);
        boolean staticReady = snapshots.stream().anyMatch(item -> item.kind() == SnapshotKind.STATIC);
        boolean runtimeReady = snapshots.stream().anyMatch(item -> item.kind() == SnapshotKind.RUNTIME || item.kind() == SnapshotKind.RUNTIME_TRACE);
        boolean runtimeTraceReady = snapshots.stream().anyMatch(item -> item.kind() == SnapshotKind.RUNTIME_TRACE);
        boolean traceabilityReady = snapshots.stream().anyMatch(item -> item.kind() == SnapshotKind.TRACEABILITY);
        return new GraphSummary(staticReady, runtimeReady, runtimeTraceReady, traceabilityReady,
                staticReady && runtimeReady ? "FUSED" : staticReady ? "STATIC_ONLY" : runtimeReady ? "DYNAMIC_ONLY" : "EMPTY",
                snapshots);
    }

    private int bounded(Integer value, int fallback, int maximum) {
        if (value == null) return fallback;
        if (value < 1 || value > maximum) throw new IllegalArgumentException("图查询参数必须在 1 到 " + maximum + " 之间");
        return value;
    }

    public record GraphSummary(boolean staticReady, boolean runtimeReady, boolean runtimeTraceReady, boolean traceabilityReady,
                               String fusionState, List<GraphRepository.GraphSnapshot> snapshots) {}

    public record GraphView(GraphRepository.GraphSnapshot snapshot, List<GraphRepository.GraphNode> nodes,
                            List<GraphRepository.GraphEdge> edges, boolean nodesClipped, boolean edgesClipped,
                            int depth, int maxNodes, int maxEdges, GraphSummary summary,
                            List<String> clipReasons, int totalActiveNodes, int edgesInScope, String expandHint) {}
}
