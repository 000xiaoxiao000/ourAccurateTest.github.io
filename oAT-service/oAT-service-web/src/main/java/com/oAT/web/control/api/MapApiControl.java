package com.oAT.web.control.api;

import com.oAT.web.api.map.MapHomePayloadService;
import com.oAT.web.api.map.MapAppPayloadService;
import com.oAT.web.api.map.TraceabilityMapPayloads.TraceabilityMapResponse;
import com.oAT.web.api.map.TraceabilityMapService;
import com.oAT.web.verification.graph.GraphService;
import com.oAT.web.common.SourceClassUtil;
import com.oAT.web.domain.ImageElement;
import com.oAT.web.esDao.StaticInfoRepository;
import com.oAT.web.esDao.entity.StaticSourceMethodInfo;
import com.oAT.web.verification.VerificationRepository;
import com.oAT.web.verification.model.VerificationModels.AssetSnapshot;
import com.oAT.web.verification.storage.AssetContentStore;
import com.oAT.web.exceptions.BusinessException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/projects/{projectId}/map")
public class MapApiControl {
    private static final String SOURCE_TREE_BEGIN = "// SOURCE_TREE_BEGIN";
    private static final String SOURCE_TREE_END = "// SOURCE_TREE_END";
    private static final String SOURCE_FILE_PREFIX = "// SOURCE_FILE:";
    private static final String FILE_PREFIX = "// FILE:";
    private static final String SNAPSHOT_ID_PREFIX = "// SNAPSHOT_ID:";
    private static final List<String> SOURCE_FILE_EXTENSIONS = List.of(
            ".java", ".kt", ".kts", ".scala", ".groovy",
            ".js", ".jsx", ".ts", ".tsx", ".vue",
            ".py", ".go", ".rs", ".c", ".cc", ".cpp", ".h", ".hpp",
            ".cs", ".php", ".rb", ".swift", ".m", ".mm",
            ".sql", ".xml", ".yaml", ".yml", ".json", ".properties",
            ".css", ".scss", ".less");

    private final MapHomePayloadService mapHomePayloadService;
    private final MapAppPayloadService mapAppPayloadService;
    private final TraceabilityMapService traceabilityMapService;
    private final GraphService graphService;
    private final StaticInfoRepository staticInfoRepository;
    private final VerificationRepository verificationRepository;
    private final AssetContentStore assetContentStore;

    public MapApiControl(MapHomePayloadService mapHomePayloadService,
                         MapAppPayloadService mapAppPayloadService,
                         TraceabilityMapService traceabilityMapService,
                         GraphService graphService,
                         StaticInfoRepository staticInfoRepository,
                         VerificationRepository verificationRepository,
                         AssetContentStore assetContentStore) {
        this.mapHomePayloadService = mapHomePayloadService;
        this.mapAppPayloadService = mapAppPayloadService;
        this.traceabilityMapService = traceabilityMapService;
        this.graphService = graphService;
        this.staticInfoRepository = staticInfoRepository;
        this.verificationRepository = verificationRepository;
        this.assetContentStore = assetContentStore;
    }

    @GetMapping("/home")
    public List<ImageElement> home(@PathVariable String projectId) {
        return mapHomePayloadService.buildHomeMapData(projectId);
    }

    @GetMapping("/traceability")
    public TraceabilityMapResponse traceability(@PathVariable String projectId,
                                                @RequestParam(required = false) String baselineId,
                                                @RequestParam(required = false) String focusId,
                                                @RequestParam(defaultValue = "BOTH") String direction,
                                                @RequestParam(defaultValue = "4") Integer depth,
                                                @RequestParam(defaultValue = "true") boolean includeStatic,
                                                @RequestParam(defaultValue = "true") boolean includeDynamic,
                                                @RequestParam(defaultValue = "true") boolean includeAiCalls,
                                                @RequestParam(defaultValue = "full") String view) {
        return traceabilityMapService.build(projectId, baselineId, focusId, direction, depth, includeStatic, includeDynamic, includeAiCalls, view);
    }

    @GetMapping("/graph/impact")
    public com.oAT.web.verification.graph.GraphImpactService.ImpactResult graphImpact(@PathVariable String projectId,
                                                                                        @RequestParam String baselineId,
                                                                                        @RequestParam String symbolId,
                                                                                        @RequestParam(required = false) Integer depth) {
        return graphService.impact(projectId, baselineId, symbolId, depth);
    }

    @org.springframework.web.bind.annotation.PostMapping("/graph/runtime-trace-project")
    public RuntimeTraceProjectionResponse projectRuntimeTraceGraph(@PathVariable String projectId,
                                                                   @RequestParam String baselineId,
                                                                   @RequestBody com.oAT.web.verification.graph.RuntimeTraceProjectionService.RuntimeTraceBatch batch) {
        var result = graphService.projectRuntimeTrace(projectId, baselineId, batch);
        return new RuntimeTraceProjectionResponse(result.snapshotId(), result.executionId(), result.nodeCount(), result.edgeCount());
    }

    public record RuntimeTraceProjectionResponse(String snapshotId, String executionId, int nodeCount, int edgeCount) {}

    @org.springframework.web.bind.annotation.PostMapping("/graph/acceptance-fusion")
    public List<AcceptanceFusionResponse> rebuildAcceptanceFusion(@PathVariable String projectId,
                                                                  @RequestParam String baselineId) {
        return graphService.rebuildAcceptanceFusion(projectId, baselineId).stream()
                .map(item -> new AcceptanceFusionResponse(item.criterionId(), item.acKey(), item.verdict().name(),
                        item.evidenceLevel().name(), item.confidence(), item.gaps()))
                .toList();
    }

    @GetMapping("/graph/acceptance-fusion")
    public List<com.oAT.web.verification.graph.GraphRepository.GraphAggregate> acceptanceFusion(@PathVariable String projectId,
                                                                                                  @RequestParam String baselineId) {
        return graphService.acceptanceFusion(projectId, baselineId);
    }

    public record AcceptanceFusionResponse(String criterionId, String acKey, String verdict, String evidenceLevel,
                                           double confidence, List<String> gaps) {}

    @org.springframework.web.bind.annotation.PostMapping("/graph/traceability-project")
    public TraceabilityGraphProjectionResponse projectTraceabilityGraph(@PathVariable String projectId,
                                                                        @RequestParam String baselineId) {
        var result = graphService.projectTraceability(projectId, baselineId);
        return new TraceabilityGraphProjectionResponse(result.snapshotId(), result.nodeCount(), result.edgeCount());
    }

    public record TraceabilityGraphProjectionResponse(String snapshotId, int nodeCount, int edgeCount) {}

    @org.springframework.web.bind.annotation.PostMapping("/graph/static-project")
    public StaticGraphProjectionResponse projectStaticGraph(@PathVariable String projectId,
                                                            @RequestParam String baselineId) {
        var result = graphService.projectStatic(projectId, baselineId);
        return new StaticGraphProjectionResponse(result.snapshotId(), result.nodeCount(), result.edgeCount());
    }

    @org.springframework.web.bind.annotation.PostMapping("/graph/runtime-coverage-project")
    public RuntimeGraphProjectionResponse projectRuntimeCoverageGraph(@PathVariable String projectId,
                                                                      @RequestParam String baselineId) {
        var result = graphService.projectRuntimeCoverage(projectId, baselineId);
        return new RuntimeGraphProjectionResponse(result.snapshotId(), result.executionId(), result.nodeCount(), result.edgeCount());
    }

    public record RuntimeGraphProjectionResponse(String snapshotId, String executionId, int nodeCount, int edgeCount) {}

    @org.springframework.web.bind.annotation.PostMapping("/graph/control-flow-project")
    public StaticGraphProjectionResponse projectControlFlowGraph(@PathVariable String projectId,
                                                                 @RequestParam String baselineId) {
        var result = graphService.projectControlFlow(projectId, baselineId);
        return new StaticGraphProjectionResponse(result.snapshotId(), result.nodeCount(), result.edgeCount());
    }

    @org.springframework.web.bind.annotation.PostMapping("/graph/static-dependency-project")
    public StaticGraphProjectionResponse projectStaticDependencyGraph(@PathVariable String projectId,
                                                                      @RequestParam String baselineId) {
        var result = graphService.projectStaticDependency(projectId, baselineId);
        return new StaticGraphProjectionResponse(result.snapshotId(), result.edgeCount(), result.edgeCount());
    }

    @org.springframework.web.bind.annotation.PostMapping("/graph/branch-coverage-project")
    public RuntimeGraphProjectionResponse projectBranchCoverageGraph(@PathVariable String projectId,
                                                                     @RequestParam String baselineId) {
        var result = graphService.projectBranchCoverage(projectId, baselineId);
        return new RuntimeGraphProjectionResponse(result.snapshotId(), result.executionId(), result.nodeCount(), result.edgeCount());
    }

    @org.springframework.web.bind.annotation.PostMapping("/graph/test-execution-project")
    public TraceabilityGraphProjectionResponse projectTestExecutionGraph(@PathVariable String projectId,
                                                                         @RequestParam String baselineId) {
        var result = graphService.projectTestExecutions(projectId, baselineId);
        return new TraceabilityGraphProjectionResponse(result.snapshotId(), result.nodeCount(), result.edgeCount());
    }

    @GetMapping("/graph/fusion-view")
    public com.oAT.web.verification.graph.FusionViewService.FusionView fusionView(@PathVariable String projectId,
                                                                                  @RequestParam String baselineId,
                                                                                  @RequestParam(required = false) Integer maxNodes) {
        return graphService.fusionView(projectId, baselineId, maxNodes);
    }

    @org.springframework.web.bind.annotation.PostMapping("/graph/assertion-consistency")
    public List<com.oAT.web.verification.graph.AssertionConsistencyService.ConsistencyResult> evaluateAssertionConsistency(
            @PathVariable String projectId, @RequestParam String baselineId) {
        return graphService.evaluateAssertionConsistency(projectId, baselineId);
    }

    @GetMapping("/graph/assertion-consistency")
    public List<com.oAT.web.verification.graph.GraphRepository.GraphAggregate> assertionConsistency(
            @PathVariable String projectId, @RequestParam String baselineId) {
        return graphService.assertionConsistency(projectId, baselineId);
    }

    @org.springframework.web.bind.annotation.PostMapping("/graph/incremental-recompute")
    public com.oAT.web.verification.graph.IncrementalRecomputeService.RecomputeResult incrementalRecompute(
            @PathVariable String projectId, @RequestParam String baselineId, @RequestBody List<String> changedSymbols) {
        return graphService.incrementalRecompute(projectId, baselineId, changedSymbols);
    }

    @org.springframework.web.bind.annotation.PostMapping("/graph/diff-invalidation")
    public com.oAT.web.verification.graph.DiffInvalidationService.InvalidationResult diffInvalidation(
            @PathVariable String projectId, @RequestParam String baselineId,
            @RequestParam com.oAT.web.verification.graph.DiffInvalidationService.ChangeType changeType) {
        return graphService.applyDiffInvalidation(projectId, baselineId, changeType);
    }

    @org.springframework.web.bind.annotation.PostMapping("/graph/baseline-succession")
    public com.oAT.web.verification.graph.DiffInvalidationService.LineageResult recordSuccession(
            @PathVariable String projectId, @RequestParam String predecessorBaselineId, @RequestParam String successorBaselineId) {
        return graphService.recordBaselineSuccession(projectId, predecessorBaselineId, successorBaselineId);
    }

    @GetMapping("/graph/baseline-lineage")
    public List<com.oAT.web.verification.graph.GraphRepository.GraphAggregate> baselineLineage(
            @PathVariable String projectId, @RequestParam String baselineId) {
        return graphService.baselineLineage(projectId, baselineId);
    }

    @org.springframework.web.bind.annotation.PostMapping("/graph/read-models/rebuild")
    public com.oAT.web.verification.graph.ReadModelService.RebuildResult rebuildReadModels(
            @PathVariable String projectId, @RequestParam String baselineId) {
        return graphService.rebuildReadModels(projectId, baselineId);
    }

    @GetMapping("/graph/read-models")
    public List<com.oAT.web.verification.graph.GraphRepository.GraphAggregate> readModel(
            @PathVariable String projectId, @RequestParam String baselineId, @RequestParam String kind) {
        return graphService.readModel(projectId, baselineId, kind);
    }

    @GetMapping("/graph/baseline-comparison")
    public com.oAT.web.verification.graph.BaselineComparisonService.ComparisonResult compareBaselines(
            @PathVariable String projectId,
            @RequestParam String baseBaselineId,
            @RequestParam String targetBaselineId) {
        return graphService.compareBaselines(projectId, baseBaselineId, targetBaselineId);
    }

    @GetMapping("/graph")
    public GraphService.GraphView graph(@PathVariable String projectId,
                                        @RequestParam String baselineId,
                                        @RequestParam(required = false) String focusId,
                                        @RequestParam(required = false) Integer depth,
                                        @RequestParam(required = false) Integer maxNodes,
                                        @RequestParam(required = false) Integer maxEdges) {
        return graphService.query(projectId, baselineId, focusId, depth, maxNodes, maxEdges);
    }

    @GetMapping("/graph/focus-candidates")
    public List<com.oAT.web.verification.graph.GraphRepository.GraphNode> graphFocusCandidates(
            @PathVariable String projectId,
            @RequestParam String baselineId,
            @RequestParam(required = false) Integer limit) {
        return graphService.focusCandidates(projectId, baselineId, limit);
    }

    public record StaticGraphProjectionResponse(String snapshotId, int nodeCount, int edgeCount) {}

    @GetMapping("/apps/{appId}")
    public List<ImageElement> app(@PathVariable String projectId,
                                  @PathVariable String appId,
                                  @RequestParam(required = false) String layers) throws BusinessException {
        return mapAppPayloadService.buildAppMapData(projectId, appId, layers);
    }

    @GetMapping("/source-tree")
    public List<SourceTreeClass> sourceTree(@PathVariable String projectId,
                                            @RequestParam(required = false) String appId,
                                            @RequestParam(required = false) String sourceAssetId) {
        // Collect results from both sources and merge, deduplicating by filePath
        List<SourceTreeClass> merged = new ArrayList<>();
        java.util.Set<String> seenPaths = new java.util.LinkedHashSet<>();

        // 1. Static index (fast, structured method data)
        if (appId != null && !appId.isBlank()) {
            staticInfoRepository.findByAppId(appId).stream()
                    .filter(info -> info.getClassInfo() != null)
                    .map(info -> {
                        String fqcn = info.getClassInfo().getClassName();
                        String filePath = fqcnToFilePath(fqcn);
                        String simpleName = simpleClassName(fqcn);
                        return new SourceTreeClass(info.getId(), simpleName, filePath,
                                info.getClassInfo().getMethodMaps() == null ? List.of()
                                        : info.getClassInfo().getMethodMaps().entrySet().stream()
                                        .map(entry -> sourceTreeMethod(entry.getKey(), entry.getValue()))
                                        .toList());
                    })
                    .forEach(cls -> {
                        if (seenPaths.add(cls.filePath())) merged.add(cls);
                    });
        }

        // 2. Source asset (contains all files including non-Java, e.g. frontend code)
        if (sourceAssetId != null && !sourceAssetId.isBlank()) {
            verificationRepository.findAsset(projectId, sourceAssetId)
                    .map(this::sourceTreeFromAsset)
                    .orElse(List.of())
                    .forEach(cls -> {
                        if (seenPaths.add(cls.filePath())) merged.add(cls);
                    });
        }

        return merged;
    }

    /**
     * Converts a FQCN to a display path rooted at "src/main/java".
     *   "com.oAT.web3.controller.UserController"
     *     → "src/main/java/com/oAT/web3/controller/UserController.java"
     * Path-like names (contain '/' or known extensions) are returned as-is.
     */
    private String fqcnToFilePath(String className) {
        if (className == null || className.isBlank()) return "src/main/java/Unknown.java";
        String normalized = className.replace('\\', '/');
        if (SourceClassUtil.isPathLikeName(normalized)) {
            String path = SourceClassUtil.normalizePathName(normalized);
            return path.endsWith(".java") ? path : path + ".java";
        }
        // Strip inner-class suffix before converting dots to slashes
        String outer = normalized.contains("$") ? normalized.substring(0, normalized.indexOf('$')) : normalized;
        String slashed = outer.replace('.', '/');
        return "src/main/java/" + slashed + ".java";
    }

    private String simpleClassName(String fqcn) {
        if (fqcn == null || fqcn.isBlank()) return "Unknown";
        String base = fqcn.contains("$") ? fqcn.substring(0, fqcn.indexOf('$')) : fqcn;
        int lastDot   = base.lastIndexOf('.');
        int lastSlash = base.lastIndexOf('/');
        int split = Math.max(lastDot, lastSlash);
        String name = split >= 0 ? base.substring(split + 1) : base;
        return name.endsWith(".java") ? name.substring(0, name.length() - 5) : name;
    }

    private List<SourceTreeClass> sourceTreeFromAsset(AssetSnapshot asset) {
        String content = asset.storageKey() == null ? asset.content() : assetContentStore.load(asset.storageKey());
        if (content == null || content.isBlank()) content = asset.contentPreview();
        if (content == null || content.isBlank()) return List.of();
        List<String> manifestFiles = new ArrayList<>();
        List<SourceTreeClass> parsedFiles = new ArrayList<>();
        String currentFile = asset.fileName() == null ? "ImportedSource.java" : asset.fileName();
        StringBuilder source = new StringBuilder();
        boolean inManifest = false;
        boolean hasFileMarker = false;
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (SOURCE_TREE_BEGIN.equals(trimmed)) {
                inManifest = true;
                continue;
            }
            if (SOURCE_TREE_END.equals(trimmed)) {
                inManifest = false;
                continue;
            }
            if (inManifest) {
                if (trimmed.startsWith(SOURCE_FILE_PREFIX)) {
                    String filePath = trimmed.substring(SOURCE_FILE_PREFIX.length()).trim();
                    if (!filePath.isBlank()) manifestFiles.add(filePath);
                }
                continue;
            }
            if (trimmed.startsWith(SNAPSHOT_ID_PREFIX)) {
                continue;
            }
            if (trimmed.startsWith(FILE_PREFIX)) {
                if (hasFileMarker || !source.isEmpty()) appendParsedSource(parsedFiles, currentFile, source.toString());
                currentFile = trimmed.substring(FILE_PREFIX.length()).trim();
                source.setLength(0);
                hasFileMarker = true;
            } else {
                source.append(line).append('\n');
            }
        }
        appendParsedSource(parsedFiles, currentFile, source.toString());
        return mergeManifestFiles(manifestFiles, parsedFiles);
    }

    private void appendParsedSource(List<SourceTreeClass> result, String fileName, String source) {
        if (source.isBlank()) return;
        String normalizedFile = fileName.replace('\\', '/');
        // Keep the original extension for non-Java files; only add .java when there is none
        String filePath = hasKnownSourceFileExtension(normalizedFile)
                ? normalizedFile
                : normalizedFile + ".java";
        Pattern classPattern = Pattern.compile("(?:class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)");
        Matcher classMatcher = classPattern.matcher(source);
        String simpleName = classMatcher.find() ? classMatcher.group(1) : simpleFileName(normalizedFile);
        Pattern methodPattern = Pattern.compile("(?m)^\\s*(?:public|protected|private|static|final|synchronized|abstract|native|default|\\s)+[\\w<>,.?\\[\\] ]+\\s+([A-Za-z_$][\\w$]*)\\s*\\([^;{}]*\\)\\s*(?:throws [^{]+)?\\{");
        Matcher methodMatcher = methodPattern.matcher(source);
        List<SourceTreeMethod> methods = new ArrayList<>();
        while (methodMatcher.find()) {
            String methodName = methodMatcher.group(1);
            int lineNumber = 1;
            for (int index = 0; index < methodMatcher.start(); index++) if (source.charAt(index) == '\n') lineNumber++;
            methods.add(new SourceTreeMethod(methodName, simpleName, lineNumber));
        }
        result.add(new SourceTreeClass(
                UUID.nameUUIDFromBytes((filePath + simpleName).getBytes()).toString(),
                simpleName,
                filePath,
                methods));
    }

    private List<SourceTreeClass> mergeManifestFiles(List<String> manifestFiles, List<SourceTreeClass> parsedFiles) {
        java.util.Map<String, SourceTreeClass> byPath = new java.util.LinkedHashMap<>();
        for (String fileName : manifestFiles) {
            String filePath = normalizeSourceFilePath(fileName);
            byPath.putIfAbsent(filePath, sourceTreeFileOnly(filePath));
        }
        for (SourceTreeClass parsedFile : parsedFiles) {
            byPath.put(parsedFile.filePath(), parsedFile);
        }
        return new ArrayList<>(byPath.values());
    }

    private SourceTreeClass sourceTreeFileOnly(String filePath) {
        String simpleName = simpleFileName(filePath);
        return new SourceTreeClass(
                UUID.nameUUIDFromBytes((filePath + simpleName).getBytes()).toString(),
                simpleName,
                filePath,
                List.of());
    }

    private String normalizeSourceFilePath(String fileName) {
        String normalizedFile = fileName == null ? "ImportedSource.java" : fileName.replace('\\', '/');
        return hasKnownSourceFileExtension(normalizedFile) ? normalizedFile : normalizedFile + ".java";
    }

    private boolean hasKnownSourceFileExtension(String value) {
        if (value == null || value.isBlank()) return false;
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        return SOURCE_FILE_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private String simpleFileName(String fileName) {
        String normalized = fileName == null ? "ImportedSource" : fileName.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private SourceTreeMethod sourceTreeMethod(String key, StaticSourceMethodInfo method) {
        List<Integer> lines = method == null ? null : method.getMethodLineNumberMap();
        return new SourceTreeMethod(
                method != null && method.getMethodName() != null ? method.getMethodName() : key,
                method == null ? null : method.getMethodDesc(),
                lines == null || lines.isEmpty() ? null : lines.get(0));
    }

    public record SourceTreeClass(String id, String className, String filePath, List<SourceTreeMethod> methods) {}
    public record SourceTreeMethod(String methodName, String methodDesc, Integer lineNumber) {}

    @GetMapping("/code")
    public List<ImageElement> code(@PathVariable String projectId,
                                   @RequestParam String traceId) {
        return mapAppPayloadService.buildTraceStackCodeData(projectId, traceId);
    }
}
