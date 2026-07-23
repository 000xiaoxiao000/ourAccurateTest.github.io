package com.oAT.web.verification.graph;

import com.oAT.web.verification.VerificationRepository;
import com.oAT.web.verification.SourceAssetFilter;
import com.oAT.web.verification.model.GraphModels.GraphNodeKind;
import com.oAT.web.verification.model.GraphModels.SnapshotKind;
import com.oAT.web.verification.model.VerificationModels.AssetSnapshot;
import com.oAT.web.verification.model.VerificationModels.Baseline;
import com.oAT.web.verification.model.VerificationModels.AssetType;
import com.oAT.web.verification.storage.AssetContentStore;
import com.oAT.web.service.AppService;
import com.oAT.web.service.entity.AppVo;
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
    private final AppService appService;
    private final AssetContentStore assetContentStore;

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
                        VerificationRepository verificationRepository,
                        AppService appService,
                        AssetContentStore assetContentStore) {
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
        this.appService = appService;
        this.assetContentStore = assetContentStore;
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
        AppVo sourceApp = appService.getApp(baseline.sourceAppId());
        if (!isJavaApp(sourceApp)) {
            return projectPolyglotStaticFromAsset(projectId, baseline, sourceApp, true);
        }
        StaticGraphProjectionService.ProjectionResult result = staticProjectionService.project(projectId, baseline.id(),
                baseline.sourceAppId(), baseline.repositoryUrl(), baseline.sourceCommit());
        if (result.nodeCount() > 0 || !StringUtils.hasText(baseline.sourceAssetId())) {
            return result;
        }
        return projectPolyglotStaticFromAsset(projectId, baseline, sourceApp, false);
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
        requireJavaSourceApp(projectId, baselineId, "控制流图");
        return cfgProjectionService.project(projectId, baselineId);
    }

    public StaticDependencyGraphProjectionService.ProjectionResult projectStaticDependency(String projectId, String baselineId) {
        verificationRepository.findBaseline(projectId, baselineId)
                .orElseThrow(() -> new IllegalArgumentException("分析基线不存在或不属于当前项目"));
        if (graphRepository.countActiveNodesByKind(baselineId, GraphNodeKind.TYPE) == 0
                && graphRepository.countActiveNodesByKind(baselineId, GraphNodeKind.SOURCE_FILE) == 0) {
            projectStatic(projectId, baselineId);
        }
        return dependencyProjectionService.project(projectId, baselineId);
    }

    public BranchCoverageProjectionService.ProjectionResult projectBranchCoverage(String projectId, String baselineId) {
        verificationRepository.findBaseline(projectId, baselineId)
                .orElseThrow(() -> new IllegalArgumentException("分析基线不存在或不属于当前项目"));
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

    public List<GraphRepository.GraphNode> focusCandidates(String projectId, String baselineId, Integer requestedLimit) {
        verificationRepository.findBaseline(projectId, baselineId)
                .orElseThrow(() -> new IllegalArgumentException("分析基线不存在或不属于当前项目"));
        int limit = bounded(requestedLimit, 5_000, 10_000);
        return graphRepository.findFocusCandidateNodes(baselineId, List.of(
                GraphNodeKind.REQUIREMENT,
                GraphNodeKind.ACCEPTANCE_CRITERION,
                GraphNodeKind.TESTCASE,
                GraphNodeKind.METHOD,
                GraphNodeKind.COVERAGE_UNIT,
                GraphNodeKind.TEST_EXECUTION
        ), limit);
    }

    private GraphSummary summary(String baselineId) {
        List<GraphRepository.GraphSnapshot> snapshots = graphRepository.findActiveSnapshots(baselineId);
        java.util.Map<String, GraphRepository.ProjectionStats> projectionStats = graphRepository.activeProjectionStats(baselineId);
        boolean staticReady = hasProjectedData(projectionStats, SnapshotKind.STATIC);
        boolean runtimeReady = hasProjectedData(projectionStats, SnapshotKind.RUNTIME) || hasProjectedData(projectionStats, SnapshotKind.RUNTIME_TRACE);
        boolean runtimeTraceReady = hasProjectedData(projectionStats, SnapshotKind.RUNTIME_TRACE);
        boolean traceabilityReady = hasProjectedData(projectionStats, SnapshotKind.TRACEABILITY);
        return new GraphSummary(staticReady, runtimeReady, runtimeTraceReady, traceabilityReady,
                staticReady && runtimeReady ? "FUSED" : staticReady ? "STATIC_ONLY" : runtimeReady ? "DYNAMIC_ONLY" : "EMPTY",
                snapshots, projectionStats);
    }

    private boolean hasProjectedData(java.util.Map<String, GraphRepository.ProjectionStats> projectionStats, SnapshotKind kind) {
        GraphRepository.ProjectionStats stats = projectionStats.get(kind.name());
        return stats != null && (stats.nodeCount() > 0 || stats.edgeCount() > 0);
    }

    private void requireJavaSourceApp(String projectId, String baselineId, String projectionName) {
        Baseline baseline = verificationRepository.findBaseline(projectId, baselineId)
                .orElseThrow(() -> new IllegalArgumentException("分析基线不存在或不属于当前项目"));
        if (!StringUtils.hasText(baseline.sourceAppId())) {
            throw new IllegalArgumentException("当前基线未指定源码工程，无法建立" + projectionName);
        }
        if (!isJavaApp(appService.getApp(baseline.sourceAppId()))) {
            throw new IllegalArgumentException(projectionName + "当前仅支持 Java 源码；该工程已使用多语言静态图投影");
        }
    }

    private boolean isJavaApp(AppVo app) {
        return app == null || !StringUtils.hasText(app.getLanguage()) || "JAVA".equalsIgnoreCase(app.getLanguage().trim());
    }

    private StaticGraphProjectionService.ProjectionResult projectPolyglotStaticFromAsset(String projectId, Baseline baseline,
                                                                                        AppVo sourceApp, boolean filterByAppProfile) {
        if (!StringUtils.hasText(baseline.sourceAssetId())) {
            throw new IllegalArgumentException("当前基线没有源码资料，无法建立静态图");
        }
        AssetSnapshot source = verificationRepository.findAsset(projectId, baseline.sourceAssetId())
                .orElseThrow(() -> new IllegalArgumentException("找不到当前基线的源码资料"));
        if (source.assetType() != AssetType.SOURCE) {
            throw new IllegalArgumentException("当前基线绑定的不是源码资料，无法建立静态图");
        }
        String rawContent = loadAssetContent(source);
        String content = filterByAppProfile ? SourceAssetFilter.filterContent(rawContent, SourceAssetFilter.fromApp(sourceApp)) : rawContent;
        if (!StringUtils.hasText(content)) {
            throw new IllegalArgumentException("当前基线的源码资料为空，无法建立静态图");
        }
        String language = filterByAppProfile && sourceApp != null && StringUtils.hasText(sourceApp.getLanguage())
                ? sourceApp.getLanguage()
                : inferLanguage(source.fileName(), rawContent);
        StaticGraphProjectionService.ProjectionResult result = polyglotStaticProjectionService.project(projectId, baseline.id(), baseline.sourceAppId(),
                baseline.repositoryUrl(), baseline.sourceCommit(), language, source.fileName(), content);
        if (result.nodeCount() == 0 && filterByAppProfile && !rawContent.equals(content)) {
            result = polyglotStaticProjectionService.project(projectId, baseline.id(), baseline.sourceAppId(),
                    baseline.repositoryUrl(), baseline.sourceCommit(), inferLanguage(source.fileName(), rawContent), source.fileName(), rawContent);
        }
        if (result.nodeCount() == 0) {
            throw new IllegalArgumentException("源码资料中没有可投影的源码文件，请检查源码资产是否包含 .vue/.ts/.js/.py/.go/.cpp 等文件内容或文件清单");
        }
        return result;
    }

    private String inferLanguage(String fileName, String content) {
        String lower = fileName == null ? "" : fileName.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".py")) return "PYTHON";
        if (lower.endsWith(".go")) return "GO";
        if (lower.endsWith(".c") || lower.endsWith(".cc") || lower.endsWith(".cpp") || lower.endsWith(".h") || lower.endsWith(".hpp")) return "CPP";
        if (lower.endsWith(".js") || lower.endsWith(".jsx") || lower.endsWith(".ts") || lower.endsWith(".tsx") || lower.endsWith(".vue")) return "FRONTEND";
        String text = content == null ? "" : content;
        if (text.contains(".vue") || text.contains(".tsx") || text.contains(".ts") || text.contains(".jsx")
                || text.contains("function ") || text.contains("const ") || text.contains("<script")) return "FRONTEND";
        if (text.contains(".py") || text.contains("def ")) return "PYTHON";
        if (text.contains(".go") || text.contains("func ")) return "GO";
        return "FRONTEND";
    }

    private String loadAssetContent(AssetSnapshot asset) {
        if (StringUtils.hasText(asset.storageKey())) {
            String stored = assetContentStore.load(asset.storageKey());
            if (StringUtils.hasText(stored)) return stored;
        }
        return asset.content() == null ? "" : asset.content();
    }

    private int bounded(Integer value, int fallback, int maximum) {
        if (value == null) return fallback;
        if (value < 1 || value > maximum) throw new IllegalArgumentException("图查询参数必须在 1 到 " + maximum + " 之间");
        return value;
    }

    public record GraphSummary(boolean staticReady, boolean runtimeReady, boolean runtimeTraceReady, boolean traceabilityReady,
                               String fusionState, List<GraphRepository.GraphSnapshot> snapshots,
                               java.util.Map<String, GraphRepository.ProjectionStats> projectionStats) {}

    public record GraphView(GraphRepository.GraphSnapshot snapshot, List<GraphRepository.GraphNode> nodes,
                            List<GraphRepository.GraphEdge> edges, boolean nodesClipped, boolean edgesClipped,
                            int depth, int maxNodes, int maxEdges, GraphSummary summary,
                            List<String> clipReasons, int totalActiveNodes, int edgesInScope, String expandHint) {}
}
