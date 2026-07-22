package com.oAT.web.api.map;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.oAT.web.common.UtilJson;
import com.oAT.web.coverage.universal.CoverageReportService;
import com.oAT.web.esDao.ClassCoverageIndexRepository;
import com.oAT.web.esDao.StaticInfoRepository;
import com.oAT.web.esDao.entity.ClassCoverageIndex;
import com.oAT.web.esDao.entity.StaticSourceInfo;
import com.oAT.web.esDao.entity.StaticSourceMethodInfo;
import com.oAT.web.exceptions.FriendlyException;
import com.oAT.web.service.AppService;
import com.oAT.web.service.entity.AppVo;
import com.oAT.web.verification.VerificationRepository;
import com.oAT.web.verification.SourceAssetFilter;
import com.oAT.web.verification.SourceAssetFilter.SourceProfile;
import com.oAT.web.verification.model.VerificationModels;
import com.oAT.web.verification.model.VerificationModels.AcceptanceCriterion;
import com.oAT.web.verification.model.VerificationModels.AssetSnapshot;
import com.oAT.web.verification.model.VerificationModels.AssetType;
import com.oAT.web.verification.model.VerificationModels.Baseline;
import com.oAT.web.verification.model.VerificationModels.TestcaseProjection;
import com.oAT.web.verification.model.VerificationModels.TraceLink;
import com.oAT.web.verification.storage.AssetContentStore;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.oAT.web.api.map.TraceabilityMapPayloads.CallEvidence;
import static com.oAT.web.api.map.TraceabilityMapPayloads.CodeTreeKind;
import static com.oAT.web.api.map.TraceabilityMapPayloads.CodeTreeNode;
import static com.oAT.web.api.map.TraceabilityMapPayloads.CodeGraphData;
import static com.oAT.web.api.map.TraceabilityMapPayloads.CodeDependency;
import static com.oAT.web.api.map.TraceabilityMapPayloads.ControlFlowStep;
import static com.oAT.web.api.map.TraceabilityMapPayloads.CoverageSummary;
import static com.oAT.web.api.map.TraceabilityMapPayloads.EdgeEvidence;
import static com.oAT.web.api.map.TraceabilityMapPayloads.EvidenceState;
import static com.oAT.web.api.map.TraceabilityMapPayloads.EvidenceType;
import static com.oAT.web.api.map.TraceabilityMapPayloads.NodeKind;
import static com.oAT.web.api.map.TraceabilityMapPayloads.Relation;
import static com.oAT.web.api.map.TraceabilityMapPayloads.TraceabilityEdge;
import static com.oAT.web.api.map.TraceabilityMapPayloads.TraceabilityMapResponse;
import static com.oAT.web.api.map.TraceabilityMapPayloads.TraceabilityNode;
import static com.oAT.web.api.map.TraceabilityMapPayloads.TraceabilitySummary;

@Service
public class TraceabilityMapService {
    private static final String SOURCE_ONLY_BASELINE_PREFIX = "source-only:";
    private static final int DEFAULT_DEPTH = 4;
    private static final int MAX_DEPTH = 8;
    private static final int MAX_NODES = 700;
    private static final int MAX_EDGES = 1200;
    private static final Pattern SOURCE_FILE_PATTERN = Pattern.compile("//\\s*SOURCE_FILE:\\s*(.+)");
    private static final Pattern CONTENT_FILE_PATTERN = Pattern.compile("//\\s*FILE:\\s*(.+)");
    private static final Pattern CLASS_PATTERN = Pattern.compile("(?:class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern IMPORT_PATTERN = Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$*]*)*);\\s*$");
    private static final Pattern CONTROL_FLOW_PATTERN = Pattern.compile("(?m)^\\s*(if|else\\s+if|else|for|while|switch|case|catch|return|throw)\\b\\s*(.*)");
    private static final Pattern METHOD_PATTERN = Pattern.compile("(?m)^\\s*(?:@[\\w.]+(?:\\([^\\n]*\\))?\\s*)*(?:(?:public|protected|private|static|final|synchronized|abstract|native|default)\\s+)*[\\w<>,.?\\[\\]]+(?:\\s*<[^\\n{};()]+>)?\\s+([A-Za-z_$][\\w$]*)\\s*\\([^;{}]*\\)\\s*(?:throws [^{]+)?\\{");
    private static final Set<String> JAVA_CONTROL_KEYWORDS = Set.of(
            "if", "for", "while", "switch", "catch", "return", "throw", "else", "case", "do", "try", "finally", "synchronized");
    private static final JavaParser JAVA_PARSER = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));

    private final VerificationRepository verificationRepository;
    private final StaticInfoRepository staticInfoRepository;
    private final AssetContentStore assetContentStore;
    private final CodeSymbolNormalizer normalizer;
    private final AppService appService;
    private final ClassCoverageIndexRepository classCoverageIndexRepository;
    private final CoverageReportService coverageReportService;

    public TraceabilityMapService(VerificationRepository verificationRepository,
                                  StaticInfoRepository staticInfoRepository,
                                  AssetContentStore assetContentStore,
                                  CodeSymbolNormalizer normalizer,
                                  AppService appService,
                                  ClassCoverageIndexRepository classCoverageIndexRepository,
                                  CoverageReportService coverageReportService) {
        this.verificationRepository = verificationRepository;
        this.staticInfoRepository = staticInfoRepository;
        this.assetContentStore = assetContentStore;
        this.normalizer = normalizer;
        this.appService = appService;
        this.classCoverageIndexRepository = classCoverageIndexRepository;
        this.coverageReportService = coverageReportService;
    }

    public TraceabilityMapResponse build(String projectId, String baselineId, String focusId, String direction,
                                         Integer requestedDepth, boolean includeStatic, boolean includeDynamic) {
        return build(projectId, baselineId, focusId, direction, requestedDepth, includeStatic, includeDynamic, false);
    }

    public TraceabilityMapResponse build(String projectId, String baselineId, String focusId, String direction,
                                         Integer requestedDepth, boolean includeStatic, boolean includeDynamic,
                                         boolean includeAiCalls) {
        return build(projectId, baselineId, focusId, direction, requestedDepth, includeStatic, includeDynamic, includeAiCalls, "full");
    }

    public TraceabilityMapResponse build(String projectId, String baselineId, String focusId, String direction,
                                         Integer requestedDepth, boolean includeStatic, boolean includeDynamic,
                                         boolean includeAiCalls, String view) {
        int depth = normalizeDepth(requestedDepth);
        boolean traceOnlyView = "trace".equalsIgnoreCase(value(view));
        boolean includeCallEdges = !traceOnlyView;
        boolean includeCodeTree = !traceOnlyView;
        boolean includeCodeGraph = !traceOnlyView;
        List<String> warnings = new ArrayList<>();
        Baseline baseline = resolveBaseline(projectId, baselineId, warnings);
        List<AcceptanceCriterion> criteria = verificationRepository.findCriteria(baseline.id());
        List<TestcaseProjection> testcases = verificationRepository.findTestcases(baseline.id());
        List<TraceLink> links = verificationRepository.findTraceLinks(baseline.id());

        GraphBuilder graph = new GraphBuilder();
        Map<String, AcceptanceCriterion> criteriaById = new HashMap<>();
        Map<String, TestcaseProjection> testcaseById = new HashMap<>();
        criteria.forEach(item -> {
            criteriaById.put(item.id(), item);
            graph.putNode(requirementNode(item));
        });
        testcases.forEach(item -> {
            testcaseById.put(item.id(), item);
            graph.putNode(testcaseNode(item));
        });

        CodeIndex codeIndex = buildCodeIndex(projectId, baseline, includeStatic, includeCallEdges || includeCodeGraph, warnings);
        DynamicEvidence dynamicEvidence = includeDynamic ? readDynamicEvidence(projectId, baseline, codeIndex, warnings) : DynamicEvidence.empty();

        // Coverage mutates the code index. Copy nodes into the response graph only afterwards.
        // Otherwise the graph would retain nodes without coverage metadata or source line numbers.
        codeIndex.nodes.values().forEach(graph::putNode);

        normalizeTraceLinks(graph, codeIndex, criteriaById, testcaseById, links);
        deriveTestcaseCodeEdges(graph, criteria, links);
        deriveMissingTraceEdges(graph, criteria, testcases, codeIndex);

        dynamicEvidence.coveredNodeIds.forEach(graph::markDynamic);

        if (includeStatic) {
            if (includeCallEdges && includeAiCalls) {
                int inferred = codeIndex.inferSourceCallPairs();
                if (inferred == 0) {
                    warnings.add("源码静态调用解析未发现可关联的代码调用关系");
                } else {
                    warnings.add("源码静态调用解析已生成 " + inferred + " 条代码调用关系");
                }
            }
            if (includeCallEdges) {
                addStaticCallEdges(graph, codeIndex, dynamicEvidence.coveredNodeIds);
            }
        }
        if (includeDynamic && includeCallEdges) {
            addDynamicCallEdges(graph, codeIndex, dynamicEvidence.callPairs, warnings);
        }

        GraphView clipped = graph.focus(focusId, direction, depth, MAX_NODES, MAX_EDGES, warnings);
        List<CodeTreeNode> codeTree = includeCodeTree ? buildCodeTree(codeIndex, Set.of(), graph.dynamicNodes) : List.of();
        TraceabilitySummary summary = summarize(criteria, testcases, clipped.nodes(), clipped.edges(), graph.dynamicNodes, warnings);

        return new TraceabilityMapResponse(
                new TraceabilityMapPayloads.BaselineInfo(
                        baseline.id(), baseline.name(), baseline.sourceAppId(), baseline.sourceAssetId(),
                        baseline.executionAssetId(), baseline.coverageAssetId(), baseline.repositoryUrl(),
                        baseline.sourceBranch(), baseline.sourceCommit(), baseline.analyzerVersion(),
                        baseline.freshness(), baseline.updateTime()),
                summary,
                clipped.nodes(),
                clipped.edges(),
                codeTree,
                includeCodeGraph ? new CodeGraphData(codeIndex.dependencies, codeIndex.controlFlows) : null,
                warnings);
    }

    private Baseline resolveBaseline(String projectId, String baselineId, List<String> warnings) {
        if (StringUtils.hasText(baselineId)) {
            if (baselineId.startsWith(SOURCE_ONLY_BASELINE_PREFIX)) {
                String assetId = baselineId.substring(SOURCE_ONLY_BASELINE_PREFIX.length());
                Optional<Baseline> sourceOnlyBaseline = verificationRepository.findAsset(projectId, assetId)
                        .filter(asset -> asset.assetType() == AssetType.SOURCE)
                        .map(this::sourceOnlyBaseline);
                if (sourceOnlyBaseline.isPresent()) {
                    return sourceOnlyBaseline.get();
                }
                return fallbackBaseline(projectId, baselineId, "请求的源码快照已不可用，已切换到当前项目可用基线", warnings)
                        .orElseThrow(() -> new FriendlyException("源码快照不存在或不属于当前项目，请重新导入源码或切换分析基线"));
            }
            Optional<Baseline> requested = verificationRepository.findBaseline(projectId, baselineId);
            if (requested.isPresent()) {
                return requested.get();
            }
            return fallbackBaseline(projectId, baselineId, "请求的分析基线已不可用，已切换到当前项目可用基线", warnings)
                    .orElseThrow(() -> new FriendlyException("分析基线不存在或不属于当前项目，请刷新后重新选择基线"));
        }
        Optional<Baseline> baseline = verificationRepository.findBaselines(projectId).stream().findFirst();
        if (baseline.isPresent()) {
            return baseline.get();
        }
        return latestSourceBaseline(projectId)
                .orElseThrow(() -> new FriendlyException("当前项目暂无分析基线或源码快照"));
    }

    private Optional<Baseline> fallbackBaseline(String projectId, String requestedBaselineId, String message, List<String> warnings) {
        Optional<Baseline> baseline = verificationRepository.findBaselines(projectId).stream().findFirst();
        Optional<Baseline> fallback = baseline.isPresent() ? baseline : latestSourceBaseline(projectId);
        fallback.ifPresent(item -> warnings.add(message + "：" + requestedBaselineId + " → " + item.id()));
        return fallback;
    }

    private Optional<Baseline> latestSourceBaseline(String projectId) {
        return verificationRepository.findAssets(projectId, AssetType.SOURCE).stream()
                .findFirst()
                .map(this::sourceOnlyBaseline);
    }

    private Baseline sourceOnlyBaseline(AssetSnapshot asset) {
        return new Baseline(
                SOURCE_ONLY_BASELINE_PREFIX + asset.id(),
                asset.projectId(),
                "源码快照 " + firstText(asset.fileName(), asset.id()),
                null,
                null,
                asset.id(),
                null,
                null,
                metadataText(asset, "appId"),
                asset.externalUrl(),
                null,
                asset.sourceVersion(),
                VerificationModels.ANALYZER_VERSION,
                VerificationModels.BaselineStatus.CREATED,
                asset.freshness(),
                asset.importedBy(),
                asset.capturedAt(),
                LocalDateTime.now());
    }

    private int normalizeDepth(Integer requestedDepth) {
        if (requestedDepth == null) {
            return DEFAULT_DEPTH;
        }
        if (requestedDepth < 1 || requestedDepth > MAX_DEPTH) {
            throw new FriendlyException("depth 必须在 1 到 " + MAX_DEPTH + " 之间");
        }
        return requestedDepth;
    }

    private TraceabilityNode requirementNode(AcceptanceCriterion item) {
        return new TraceabilityNode(reqId(item.id()), NodeKind.REQUIREMENT,
                compactLabel(item.requirementKey(), item.acKey(), item.id()),
                firstText(item.title(), item.content()), item.sourceLocator(), "REQUIREMENT",
                null, item.acKey(), null, EvidenceState.NONE, null,
                Map.of("requirementKey", value(item.requirementKey()), "testable", item.testable(), "ambiguity", item.ambiguity()));
    }

    private TraceabilityNode testcaseNode(TestcaseProjection item) {
        return new TraceabilityNode(tcId(item.id()), NodeKind.TESTCASE,
                firstText(item.externalKey(), item.title(), item.id()),
                firstText(item.title(), item.expected(), item.steps()), item.sourceLocator(), "TESTCASE",
                null, item.externalKey(), null, EvidenceState.NONE, null,
                Map.of("requirementRefs", value(item.requirementRefs())));
    }

    private CodeIndex buildCodeIndex(String projectId, Baseline baseline, boolean includeStatic, boolean collectCodeAnalysis, List<String> warnings) {
        CodeIndex index = new CodeIndex();
        AppVo sourceApp = StringUtils.hasText(baseline.sourceAppId()) ? appService.getApp(baseline.sourceAppId()) : null;
        SourceProfile sourceProfile = SourceAssetFilter.fromApp(sourceApp);
        if (includeStatic) {
            for (String appId : projectAppIds(projectId, baseline.sourceAppId())) {
                for (StaticSourceInfo info : staticInfoRepository.findByAppId(appId)) {
                    addStaticInfo(index, info, collectCodeAnalysis);
                }
            }
        }
        Set<String> loadedAssetIds = new LinkedHashSet<>();
        Set<String> warnedAssetIds = new LinkedHashSet<>();
        if (StringUtils.hasText(baseline.sourceAssetId())) {
            Optional<AssetSnapshot> selectedSource = verificationRepository.findAsset(projectId, baseline.sourceAssetId());
            selectedSource.ifPresent(asset -> {
                if (SourceAssetFilter.assetMatchesApp(asset, sourceProfile)) {
                    warnIfTruncatedSource(asset, warnings, warnedAssetIds);
                    addSourceAsset(index, asset, loadedAssetIds, collectCodeAnalysis, sourceProfile);
                } else {
                    warnings.add("当前基线源码资产不属于所选源码工程，已按源码工程设置忽略该资产");
                }
            });
        }
        List<String> appOrder = projectAppIds(projectId, baseline.sourceAppId());
        verificationRepository.findAssets(projectId, AssetType.SOURCE).stream()
                .filter(asset -> SourceAssetFilter.assetMatchesApp(asset, sourceProfile))
                .sorted(Comparator
                        .comparingInt((AssetSnapshot asset) -> sourceAssetOrder(asset, appOrder))
                        .thenComparing(AssetSnapshot::capturedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .forEach(asset -> {
                    warnIfTruncatedSource(asset, warnings, warnedAssetIds);
                    addSourceAsset(index, asset, loadedAssetIds, collectCodeAnalysis, sourceProfile);
                });
        if (index.nodes.isEmpty() && includeStatic) {
            warnings.add("缺少可用源码索引或源码资产，仅返回需求与测试追溯关系");
        } else if (includeStatic && collectCodeAnalysis && index.pendingStaticCalls.isEmpty() && index.methodSpans.isEmpty()) {
            warnings.add("缺少静态调用索引，代码调用图已降级为代码树展示");
        }
        return index;
    }

    private List<String> projectAppIds(String projectId, String preferredAppId) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (StringUtils.hasText(preferredAppId)) {
            ids.add(preferredAppId);
            return new ArrayList<>(ids);
        }
        for (AppVo app : appService.getAppList(projectId)) {
            if (app != null && StringUtils.hasText(app.getId())) {
                ids.add(app.getId());
            }
        }
        return new ArrayList<>(ids);
    }

    private int sourceAssetOrder(AssetSnapshot asset, List<String> appOrder) {
        String appId = metadataText(asset, "appId");
        int appIndex = appOrder.indexOf(appId);
        return appIndex < 0 ? appOrder.size() + 1 : appIndex;
    }

    private void warnIfTruncatedSource(AssetSnapshot asset, List<String> warnings, Set<String> warnedAssetIds) {
        if (asset == null || !warnedAssetIds.add(asset.id()) || !Boolean.parseBoolean(value(metadataText(asset, "truncated")))) {
            return;
        }
        String sampled = firstText(metadataText(asset, "sampledFileCount"), "?");
        String total = firstText(metadataText(asset, "totalFileCount"), metadataText(asset, "fileCount"), "?");
        warnings.add("源码快照已截断：仅导入 " + sampled + "/" + total + " 个源码文件，未导入的模块不会出现在代码树和调用图中，请重新导入完整源码。");
    }

    private String metadataText(AssetSnapshot asset, String key) {
        if (asset == null || asset.metadata() == null || !asset.metadata().containsKey(key)) return null;
        Object value = asset.metadata().get(key);
        return value == null ? null : String.valueOf(value);
    }

    private void addStaticInfo(CodeIndex index, StaticSourceInfo info, boolean collectCodeAnalysis) {
        if (info.getClassInfo() == null) {
            return;
        }
        String className = info.getClassInfo().getClassName();
        String path = normalizer.pathFromClassName(className);
        String fileId = normalizer.fileId(CodeSymbolNormalizer.DEFAULT_LANGUAGE, path);
        String classId = normalizer.classId(CodeSymbolNormalizer.DEFAULT_LANGUAGE, path, className);
        Map<String, Object> fileMetadata = new LinkedHashMap<>();
        fileMetadata.put("source", "static-index");
        fileMetadata.put("sourceContent", sourceSnippet(info.getClassInfo().getSourceCode()));
        fileMetadata.put("sourceStartLine", 1);
        index.putCodeNode(new TraceabilityNode(fileId, NodeKind.CODE_FILE, normalizer.simpleFileName(path), path, path,
                "CODE", CodeSymbolNormalizer.DEFAULT_LANGUAGE, path, null, EvidenceState.STATIC, null, fileMetadata));
        Map<String, Object> classMetadata = new LinkedHashMap<>();
        classMetadata.put("staticId", value(info.getId()));
        classMetadata.put("sourceContent", sourceSnippet(info.getClassInfo().getSourceCode()));
        classMetadata.put("sourceStartLine", 1);
        index.putCodeNode(new TraceabilityNode(classId, NodeKind.CODE_CLASS, simpleClassName(className), className, path,
                "CODE", CodeSymbolNormalizer.DEFAULT_LANGUAGE, className, fileId, EvidenceState.STATIC, null, classMetadata));
        index.alias(className, classId);
        index.alias(path, classId);
        index.alias(info.getId(), classId);

        Map<String, StaticSourceMethodInfo> methods = info.getClassInfo().getMethodMaps();
        if (methods == null) {
            return;
        }
        for (Map.Entry<String, StaticSourceMethodInfo> entry : methods.entrySet()) {
            StaticSourceMethodInfo method = entry.getValue();
            String methodName = firstText(method == null ? null : method.getMethodName(), entry.getKey());
            String desc = method == null ? null : method.getMethodDesc();
            String methodId = normalizer.methodId(CodeSymbolNormalizer.DEFAULT_LANGUAGE, path, className, methodName, desc);
            Integer line = firstLine(method);
            Map<String, Object> methodMetadata = new LinkedHashMap<>();
            methodMetadata.put("line", line == null ? "" : line);
            methodMetadata.put("descriptor", value(desc));
            methodMetadata.put("recursive", Boolean.TRUE.equals(method == null ? null : method.getRecursiveMap()));
            index.putCodeNode(new TraceabilityNode(methodId, NodeKind.CODE_METHOD, methodName, desc, line == null ? path : path + ":" + line,
                    "CODE", CodeSymbolNormalizer.DEFAULT_LANGUAGE, className + "#" + methodName, classId, EvidenceState.STATIC,
                    null, methodMetadata));
            index.alias(methodName, methodId);
            index.alias(className + "#" + methodName, methodId);
            index.alias(className + "." + methodName, methodId);
            index.alias(path + "#" + methodName, methodId);
            if (StringUtils.hasText(desc)) {
                index.alias(className + "#" + methodName + desc, methodId);
                index.alias(className + "." + methodName + desc, methodId);
            }
            if (collectCodeAnalysis) {
                addInvocationPairs(index, methodId, method);
            }
        }
        if (collectCodeAnalysis) {
            collectMethodSpans(info.getClassInfo().getSourceCode(), className)
                    .forEach(span -> {
                        String methodId = index.resolve(className + "#" + span.methodName());
                        if (methodId != null && index.nodes.containsKey(methodId)) {
                            index.methodSpans.add(new MethodSpan(methodId, className, span.methodName(), span.body(), inferredInvocations(span.body())));
                        }
                    });
        }
    }

    private void addInvocationPairs(CodeIndex index, String callerId, StaticSourceMethodInfo method) {
        if (method == null || method.getInvocations() == null) {
            return;
        }
        for (StaticSourceMethodInfo.InvocationInfo invocation : method.getInvocations()) {
            if (invocation == null || !StringUtils.hasText(invocation.getName())) {
                continue;
            }
            String calleeKey = (value(invocation.getOwner()).replace('/', '.') + "#" + invocation.getName() + value(invocation.descriptorValue()));
            index.pendingStaticCalls.add(new PendingCall(callerId, calleeKey, "STATIC_INVOCATION_INDEX", invocation.getOpcode()));
        }
    }

    private void addSourceAsset(CodeIndex index, AssetSnapshot asset, Set<String> loadedAssetIds, boolean collectCodeAnalysis, SourceProfile sourceProfile) {
        if (asset == null || !loadedAssetIds.add(asset.id())) {
            return;
        }
        String content = SourceAssetFilter.filterContent(assetContent(asset), sourceProfile);
        if (!StringUtils.hasText(content)) {
            return;
        }
        List<SourceUnit> units = splitSourceUnits(asset.fileName(), content);
        for (SourceUnit unit : units) {
            String path = normalizer.normalizePath(unit.path());
            String fileId = normalizer.fileId(languageFromPath(path), path);
            Map<String, Object> fileMetadata = new LinkedHashMap<>();
            fileMetadata.put("sourceAssetId", asset.id());
            fileMetadata.put("sourceContent", sourceSnippet(unit.content()));
            fileMetadata.put("sourceStartLine", 1);
            index.putCodeNode(new TraceabilityNode(fileId, NodeKind.CODE_FILE, normalizer.simpleFileName(path), path, path,
                    "CODE", languageFromPath(path), path, null, EvidenceState.STATIC, null, fileMetadata));
            if (collectCodeAnalysis) {
                Matcher importMatcher = IMPORT_PATTERN.matcher(unit.content());
                while (importMatcher.find() && index.dependencies.size() < 5000) {
                    index.dependencies.add(new CodeDependency(normalizer.simpleFileName(path), importMatcher.group(1), "IMPORT"));
                }
            }
            List<ParsedSourceClass> parsedClasses = parseJavaSource(path, unit.content());
            if (parsedClasses.isEmpty()) {
                addSourceAssetWithRegexFallback(index, asset, collectCodeAnalysis, unit, fileId);
                continue;
            }
            for (ParsedSourceClass parsedClass : parsedClasses) {
                String classId = normalizer.classId(languageFromPath(path), path, parsedClass.className());
                Map<String, Object> classMetadata = new LinkedHashMap<>();
                classMetadata.put("sourceAssetId", asset.id());
                classMetadata.put("sourceContent", sourceSnippet(unit.content()));
                classMetadata.put("sourceStartLine", 1);
                index.putCodeNode(new TraceabilityNode(classId, NodeKind.CODE_CLASS, simpleClassName(parsedClass.className()), parsedClass.className(), path,
                        "CODE", languageFromPath(path), parsedClass.className(), fileId, EvidenceState.STATIC, null, classMetadata));
                index.alias(path, classId);
                index.alias(parsedClass.className(), classId);
                for (ParsedSourceMethod method : parsedClass.methods()) {
                    String methodId = normalizer.methodId(languageFromPath(path), path, parsedClass.className(), method.methodName(), method.descriptor());
                    Map<String, Object> metadata = new LinkedHashMap<>();
                    metadata.put("line", method.line());
                    metadata.put("sourceAssetId", asset.id());
                    metadata.put("visibility", method.visibility());
                    metadata.put("staticMethod", method.staticMethod());
                    metadata.put("descriptor", method.descriptor());
                    index.putCodeNode(new TraceabilityNode(methodId, NodeKind.CODE_METHOD, method.methodName(), method.signature(), path + ":" + method.line(),
                            "CODE", languageFromPath(path), parsedClass.className() + "#" + method.methodName(), classId, EvidenceState.STATIC,
                            null, metadata));
                    index.alias(method.methodName(), methodId);
                    index.alias(parsedClass.className() + "#" + method.methodName(), methodId);
                    index.alias(parsedClass.className() + "." + method.methodName(), methodId);
                    index.alias(path + "#" + method.methodName(), methodId);
                    index.alias(parsedClass.className() + "#" + method.methodName() + method.descriptor(), methodId);
                    index.alias(parsedClass.className() + "." + method.methodName() + method.descriptor(), methodId);
                    if (collectCodeAnalysis) {
                        index.methodSpans.add(new MethodSpan(methodId, parsedClass.className(), method.methodName(), method.body(), method.invocations()));
                        addControlFlowSteps(index, methodId, method.methodName(), method.body());
                    }
                }
            }
        }
    }

    private void addSourceAssetWithRegexFallback(CodeIndex index, AssetSnapshot asset, boolean collectCodeAnalysis, SourceUnit unit, String fileId) {
        String path = normalizer.normalizePath(unit.path());
        String className = extractClassName(path, unit.content());
        String classId = normalizer.classId(languageFromPath(path), path, className);
        Map<String, Object> classMetadata = new LinkedHashMap<>();
        classMetadata.put("sourceAssetId", asset.id());
        classMetadata.put("sourceContent", sourceSnippet(unit.content()));
        classMetadata.put("sourceStartLine", 1);
        index.putCodeNode(new TraceabilityNode(classId, NodeKind.CODE_CLASS, className, className, path,
                "CODE", languageFromPath(path), className, fileId, EvidenceState.STATIC, null, classMetadata));
        index.alias(path, classId);
        index.alias(className, classId);
        Matcher matcher = METHOD_PATTERN.matcher(unit.content());
        while (matcher.find()) {
            String methodName = matcher.group(1);
            if (JAVA_CONTROL_KEYWORDS.contains(methodName)) {
                continue;
            }
            int line = lineNumber(unit.content(), matcher.start());
            String methodId = normalizer.methodId(languageFromPath(path), path, className, methodName, null);
            String declaration = unit.content().substring(matcher.start(), matcher.end());
            int bodyEnd = methodBodyEnd(unit.content(), matcher.end() - 1);
            String body = bodyEnd > matcher.start() ? unit.content().substring(matcher.start(), bodyEnd) : "";
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("line", line);
            metadata.put("sourceAssetId", asset.id());
            metadata.put("visibility", declaration.contains("private") ? "PRIVATE" : declaration.contains("protected") ? "PROTECTED" : "PUBLIC");
            metadata.put("staticMethod", declaration.contains("static"));
            index.putCodeNode(new TraceabilityNode(methodId, NodeKind.CODE_METHOD, methodName, "", path + ":" + line,
                    "CODE", languageFromPath(path), className + "#" + methodName, classId, EvidenceState.STATIC,
                    null, metadata));
            index.alias(methodName, methodId);
            index.alias(className + "#" + methodName, methodId);
            index.alias(path + "#" + methodName, methodId);
            if (collectCodeAnalysis) {
                index.methodSpans.add(new MethodSpan(methodId, className, methodName, body, inferredInvocations(body)));
                addControlFlowSteps(index, methodId, methodName, body);
            }
        }
    }

    private void addControlFlowSteps(CodeIndex index, String methodId, String methodLabel, String body) {
        if (!StringUtils.hasText(body)) return;
        Matcher matcher = CONTROL_FLOW_PATTERN.matcher(body);
        int order = 0;
        while (matcher.find() && index.controlFlows.size() < 10000) {
            index.controlFlows.add(new ControlFlowStep(methodId, methodLabel, matcher.group(1).toUpperCase(Locale.ROOT),
                    shortenGraphText(matcher.group(2), 180), order++));
        }
    }

    private String shortenGraphText(String text, int max) {
        String normalized = value(text).replaceAll("\\s+", " ").trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max - 1) + "…";
    }

    private void normalizeTraceLinks(GraphBuilder graph, CodeIndex codeIndex,
                                     Map<String, AcceptanceCriterion> criteriaById,
                                     Map<String, TestcaseProjection> testcaseById,
                                     List<TraceLink> links) {
        for (TraceLink link : links) {
            String source = resolveTraceNode(link.sourceType(), link.sourceId(), codeIndex);
            String target = resolveTraceNode(link.targetType(), link.targetId(), codeIndex);
            if (!StringUtils.hasText(source) || !StringUtils.hasText(target)) {
                continue;
            }
            if (source.startsWith("req:") && !criteriaById.containsKey(source.substring(4))) {
                continue;
            }
            if (target.startsWith("tc:") && !testcaseById.containsKey(target.substring(3))) {
                continue;
            }
            NormalizedRelation pair = normalizePair(source, target);
            if (pair == null) {
                continue;
            }
            graph.putEdge(new TraceabilityEdge(
                    "trace:" + link.id(), pair.source(), pair.target(), pair.relation(), "FORWARD",
                    evidenceType(link), pair.relation() == Relation.CALLS ? CallEvidence.STATIC_ONLY : null,
                    link.confidence(), link.evidenceLevel(), value(link.generationMethod()),
                    link.reviewStatus(), List.of(edgeEvidence(null, link.evidence(), "TraceLink:" + link.id()))));
        }
    }

    private void deriveTestcaseCodeEdges(GraphBuilder graph, List<AcceptanceCriterion> criteria, List<TraceLink> links) {
        Set<String> explicitReqTc = new LinkedHashSet<>();
        Set<String> reqCode = new LinkedHashSet<>();
        for (TraceabilityEdge edge : graph.edges.values()) {
            if (edge.relation() == Relation.VERIFIED_BY) {
                explicitReqTc.add(edge.source() + "->" + edge.target());
            }
            if (edge.relation() == Relation.IMPLEMENTED_BY) {
                reqCode.add(edge.source() + "->" + edge.target());
            }
        }
        for (String reqTc : explicitReqTc) {
            String req = reqTc.substring(0, reqTc.indexOf("->"));
            String tc = reqTc.substring(reqTc.indexOf("->") + 2);
            for (String pair : reqCode) {
                if (!pair.startsWith(req + "->")) {
                    continue;
                }
                String code = pair.substring(pair.indexOf("->") + 2);
                graph.putEdge(new TraceabilityEdge("derived:" + stableId(tc + code), tc, code, Relation.COVERS,
                        "FORWARD", EvidenceType.DERIVED, null, 0.55,
                        VerificationModels.EvidenceLevel.E1, "DERIVED_FROM_REQUIREMENT_CODE",
                        VerificationModels.ReviewStatus.PENDING,
                        List.of(new EdgeEvidence(null, null, null, null, null,
                                "同一需求存在显式需求-测试链接和需求-代码链接，派生测试-代码候选关系", Map.of()))));
            }
        }
    }

    /**
     * Older verification results may contain only requirement/testcase links,
     * or only human-readable SOURCE_SYMBOL links. Rebuild the missing middle
     * and lower-layer edges from the same baseline data so the map remains a
     * real three-layer graph instead of three disconnected lanes.
     */
    private void deriveMissingTraceEdges(GraphBuilder graph, List<AcceptanceCriterion> criteria,
                                         List<TestcaseProjection> testcases, CodeIndex codeIndex) {
        Map<String, String> requirementKeys = new LinkedHashMap<>();
        for (AcceptanceCriterion criterion : criteria) {
            String requirementId = reqId(criterion.id());
            requirementKeys.put(normalizer.normalizeLookupKey(criterion.id()), requirementId);
            requirementKeys.put(normalizer.normalizeLookupKey(criterion.requirementKey()), requirementId);
            requirementKeys.put(normalizer.normalizeLookupKey(criterion.acKey()), requirementId);
        }

        Map<String, Set<String>> testcaseRequirements = new LinkedHashMap<>();
        for (TestcaseProjection testcase : testcases) {
            String testcaseId = tcId(testcase.id());
            Set<String> requirements = new LinkedHashSet<>();
            for (String reference : value(testcase.requirementRefs()).split("[,;，；\\s]+")) {
                String requirementId = requirementKeys.get(normalizer.normalizeLookupKey(reference));
                if (requirementId != null) {
                    requirements.add(requirementId);
                    putDerivedEdge(graph, "trace:ref:" + stableId(requirementId + testcaseId),
                            requirementId, testcaseId, Relation.VERIFIED_BY,
                            "测试用例 requirementRefs 显式引用需求");
                }
            }
            testcaseRequirements.put(testcaseId, requirements);
        }

        // Include links already present in the verification result.
        for (TraceabilityEdge edge : graph.edges.values()) {
            if (edge.relation() == Relation.VERIFIED_BY) {
                testcaseRequirements.computeIfAbsent(edge.target(), ignored -> new LinkedHashSet<>()).add(edge.source());
            }
        }

        List<TraceabilityNode> codeNodes = codeIndex.nodes.values().stream()
                .filter(node -> node.kind() == NodeKind.CODE_CLASS || node.kind() == NodeKind.CODE_METHOD)
                .toList();
        for (AcceptanceCriterion criterion : criteria) {
            String requirementId = reqId(criterion.id());
            Set<String> matchedCode = matchCodeNodes(
                    searchable(criterion.requirementKey(), criterion.acKey(), criterion.title(), criterion.content()),
                    codeIndex, codeNodes, 12);
            for (String codeId : matchedCode) {
                putDerivedEdge(graph, "trace:inferred:req-direct:" + stableId(requirementId + codeId),
                        requirementId, codeId, Relation.IMPLEMENTED_BY,
                        "需求文本匹配源码符号");
            }
        }
        for (TestcaseProjection testcase : testcases) {
            String testcaseId = tcId(testcase.id());
            String testcaseText = searchable(testcase.title(), testcase.steps(), testcase.expected(), testcase.sourceLocator());
            Set<String> matchedCode = matchCodeNodes(testcaseText, codeIndex, codeNodes, 12);
            for (String requirementId : testcaseRequirements.getOrDefault(testcaseId, Set.of())) {
                String requirementText = criteria.stream()
                        .filter(item -> reqId(item.id()).equals(requirementId))
                        .map(item -> searchable(item.requirementKey(), item.acKey(), item.title(), item.content()))
                        .findFirst().orElse("");
                matchedCode.addAll(matchCodeNodes(requirementText, codeIndex, codeNodes, 12));
                for (String codeId : matchedCode) {
                    putDerivedEdge(graph, "trace:inferred:req:" + stableId(requirementId + codeId),
                            requirementId, codeId, Relation.IMPLEMENTED_BY,
                            "需求和测试用例文本匹配源码符号");
                    putDerivedEdge(graph, "trace:inferred:tc:" + stableId(testcaseId + codeId),
                            testcaseId, codeId, Relation.COVERS,
                            "测试用例和需求文本匹配源码符号");
                }
            }
        }
    }

    private Set<String> matchCodeNodes(String text, CodeIndex codeIndex,
                                       List<TraceabilityNode> codeNodes, int limit) {
        Set<String> matched = new LinkedHashSet<>();
        String normalizedText = normalizer.normalizeLookupKey(text);
        if (!StringUtils.hasText(normalizedText)) return matched;
        codeIndex.aliases.entrySet().stream()
                .filter(entry -> entry.getKey().length() >= 4 && normalizedText.contains(entry.getKey()))
                .sorted((left, right) -> Integer.compare(right.getKey().length(), left.getKey().length()))
                .map(Map.Entry::getValue)
                .filter(id -> codeNodes.stream().anyMatch(node -> node.id().equals(id)))
                .limit(limit)
                .forEach(matched::add);
        return matched;
    }

    private String searchable(String... values) {
        return String.join(" ", java.util.Arrays.stream(values).map(this::value).toList());
    }

    private void putDerivedEdge(GraphBuilder graph, String id, String source, String target,
                                Relation relation, String reason) {
        graph.putEdge(new TraceabilityEdge(id, source, target, relation, "FORWARD", EvidenceType.AI,
                null, 0.62, VerificationModels.EvidenceLevel.E1, "TRACEABILITY_TEXT_MATCH",
                VerificationModels.ReviewStatus.PENDING,
                List.of(new EdgeEvidence(null, null, null, null, null, reason, Map.of()))));
    }

    private DynamicEvidence readDynamicEvidence(String projectId, Baseline baseline, CodeIndex codeIndex, List<String> warnings) {
        Set<String> covered = new LinkedHashSet<>();
        List<CallPair> calls = new ArrayList<>();
        if (StringUtils.hasText(baseline.coverageAssetId())) {
            List<ClassCoverageIndex> coverageIndexes = classCoverageIndexRepository.findByReportId(baseline.coverageAssetId());
            if (coverageIndexes.isEmpty() || requiresMethodCoverageRefresh(coverageIndexes)) {
                Optional<AssetSnapshot> coverageAsset = verificationRepository.findAsset(projectId, baseline.coverageAssetId());
                coverageIndexes = parseCoverageIndexesFromAsset(coverageAsset.orElse(null), baseline.sourceAppId(), warnings);
                if (coverageIndexes.isEmpty()) {
                    coverageAsset.ifPresent(asset -> covered.addAll(matchCodeMentions(assetContent(asset), codeIndex)));
                }
            }
            if (!coverageIndexes.isEmpty()) {
                for (ClassCoverageIndex index : coverageIndexes) {
                    String nodeId = codeIndex.resolveCoverageNode(index);
                    if (nodeId != null) {
                        codeIndex.applyCoverageSummaryWithParents(nodeId, coverageSummary(index), coverageMetadata(index));
                        codeIndex.applyMethodCoverage(index);
                    }
                    if (index.getCoveredLines() <= 0 && index.getCoveredBranchTargets() <= 0 && index.getCoveredMethods() <= 0) {
                        continue;
                    }
                    if (nodeId != null) {
                        covered.add(nodeId);
                    }
                }
            } else {
                warnings.add("覆盖率报告未解析出可匹配的行/分支数据，仅展示动态证据匹配结果");
            }
        }
        if (StringUtils.hasText(baseline.executionAssetId())) {
            Optional<AssetSnapshot> asset = verificationRepository.findAsset(projectId, baseline.executionAssetId());
            if (asset.isPresent()) {
                String content = assetContent(asset.get());
                covered.addAll(matchCodeMentions(content, codeIndex));
                calls.addAll(parseCallPairs(content, codeIndex, asset.get().id()));
                if (calls.isEmpty() && StringUtils.hasText(content)) {
                    warnings.add("执行资产未发现明确 caller/callee 调用顺序，未生成动态调用边");
                }
            }
        }
        if (!StringUtils.hasText(baseline.executionAssetId()) && !StringUtils.hasText(baseline.coverageAssetId())) {
            warnings.add("当前基线无动态执行或覆盖资产，仅展示静态追溯关系");
        }
        return new DynamicEvidence(covered, calls);
    }

    private boolean requiresMethodCoverageRefresh(List<ClassCoverageIndex> indexes) {
        for (ClassCoverageIndex index : indexes) {
            if (index == null || index.getMethods() == null) continue;
            for (ClassCoverageIndex.MethodCoverageDetail method : index.getMethods()) {
                if (method != null && !"file".equals(method.getMethodDesc())
                        && (method.getStartLine() <= 0 || !StringUtils.hasText(method.getClassName()))) {
                    return true;
                }
                if (index.getTotalBranchTargets() > 0
                        && method != null
                        && !"file".equals(method.getMethodDesc())
                        && method.getTotalBranchTargetProbeMap() == null) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<ClassCoverageIndex> parseCoverageIndexesFromAsset(AssetSnapshot asset, String appId, List<String> warnings) {
        if (asset == null || !StringUtils.hasText(appId)) return List.of();
        try {
            AppVo app = appService.getApp(appId);
            if (app == null) return List.of();
            return coverageReportService.parse(app, assetContent(asset).getBytes(StandardCharsets.UTF_8)).stream()
                    .map(file -> file.toClassCoverageIndex(appId))
                    .toList();
        } catch (RuntimeException exception) {
            warnings.add("覆盖率报告即时解析失败：" + exception.getMessage());
            return List.of();
        }
    }

    private Set<String> matchCodeMentions(String content, CodeIndex codeIndex) {
        Set<String> result = new LinkedHashSet<>();
        if (!StringUtils.hasText(content)) {
            return result;
        }
        String normalizedContent = normalizer.normalizeLookupKey(content);
        for (Map.Entry<String, String> alias : codeIndex.aliases.entrySet()) {
            String key = alias.getKey();
            if (key.length() >= 4 && normalizedContent.contains(key)) {
                result.add(alias.getValue());
            }
        }
        return result;
    }

    private List<CallPair> parseCallPairs(String content, CodeIndex codeIndex, String assetId) {
        if (!StringUtils.hasText(content)) {
            return List.of();
        }
        List<CallPair> result = new ArrayList<>();
        try {
            JsonNode root = UtilJson.getObjectMapper().readTree(content);
            collectCallPairs(root, codeIndex, assetId, result);
        } catch (Exception ignored) {
            Pattern pattern = Pattern.compile("caller\\s*[:=]\\s*([^,\\n\\r]+).*?callee\\s*[:=]\\s*([^,\\n\\r]+)", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(content);
            while (matcher.find()) {
                addCallPair(codeIndex, assetId, result, matcher.group(1), matcher.group(2), null, null);
            }
        }
        return result;
    }

    private void collectCallPairs(JsonNode node, CodeIndex codeIndex, String assetId, List<CallPair> result) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            JsonNode caller = firstField(node, "caller", "callerId", "source", "from", "parent");
            JsonNode callee = firstField(node, "callee", "calleeId", "target", "to", "child");
            if (caller != null && callee != null) {
                String traceId = text(firstField(node, "traceId", "trace_id"));
                String caseName = text(firstField(node, "caseName", "case", "testcase", "testCase"));
                addCallPair(codeIndex, assetId, result, caller.asText(), callee.asText(), traceId, caseName);
            }
            node.fields().forEachRemaining(entry -> collectCallPairs(entry.getValue(), codeIndex, assetId, result));
            return;
        }
        if (node.isArray()) {
            node.forEach(item -> collectCallPairs(item, codeIndex, assetId, result));
        }
    }

    private void addCallPair(CodeIndex codeIndex, String assetId, List<CallPair> result,
                             String callerRaw, String calleeRaw, String traceId, String caseName) {
        String caller = codeIndex.resolve(callerRaw);
        String callee = codeIndex.resolve(calleeRaw);
        if (caller != null && callee != null && !caller.equals(callee)) {
            result.add(new CallPair(caller, callee, assetId, traceId, caseName));
        }
    }

    private void addStaticCallEdges(GraphBuilder graph, CodeIndex codeIndex, Set<String> coveredNodeIds) {
        codeIndex.resolvePendingCalls();
        for (CallPair pair : codeIndex.staticCallPairs) {
            CallEvidence evidence = coveredNodeIds.contains(pair.source()) && coveredNodeIds.contains(pair.target())
                    ? CallEvidence.STATIC_BRIDGED : CallEvidence.STATIC_ONLY;
            boolean inferredFromSource = "SOURCE_CALL_ANALYSIS".equals(pair.assetId());
            graph.putEdge(new TraceabilityEdge("call:static:" + stableId(pair.source() + pair.target() + value(pair.assetId())), pair.source(), pair.target(),
                    Relation.CALLS, "FORWARD", EvidenceType.STATIC_ANALYSIS, evidence, evidence == CallEvidence.STATIC_BRIDGED ? 0.72 : 0.6,
                    VerificationModels.EvidenceLevel.E2, inferredFromSource ? "SOURCE_CALL_ANALYSIS" : evidence.name(), VerificationModels.ReviewStatus.PENDING,
                    List.of(new EdgeEvidence(null, null, null, null, null,
                            inferredFromSource ? "基于源码方法体静态解析生成的候选调用关系"
                                    : evidence == CallEvidence.STATIC_BRIDGED ? "两端均有覆盖证据，调用方向来自静态索引" : "静态方法调用索引",
                            callMetadata(pair)))));
        }
    }

    private void addDynamicCallEdges(GraphBuilder graph, CodeIndex codeIndex, List<CallPair> callPairs, List<String> warnings) {
        for (CallPair pair : callPairs) {
            graph.markDynamic(pair.source());
            graph.markDynamic(pair.target());
            graph.putEdge(new TraceabilityEdge("call:dynamic:" + stableId(pair.source() + pair.target() + pair.traceId()),
                    pair.source(), pair.target(), Relation.CALLS, "FORWARD", EvidenceType.EXECUTION_TRACE,
                    CallEvidence.DYNAMIC_CONFIRMED, 0.95, VerificationModels.EvidenceLevel.E4,
                    "EXECUTION_TRACE", VerificationModels.ReviewStatus.PENDING,
                    List.of(new EdgeEvidence(pair.assetId(), pair.traceId(), pair.caseName(), null, null,
                            "执行 trace 明确记录 caller/callee", Map.of()))));
        }
    }

    private TraceabilitySummary summarize(List<AcceptanceCriterion> criteria, List<TestcaseProjection> testcases,
                                          List<TraceabilityNode> nodes, List<TraceabilityEdge> edges,
                                          Set<String> dynamicNodes, List<String> warnings) {
        Set<String> reqWithTest = new HashSet<>();
        Set<String> tcWithCode = new HashSet<>();
        Set<String> completeReq = new HashSet<>();
        Map<String, Set<String>> reqToTc = new HashMap<>();
        Map<String, Set<String>> tcToCode = new HashMap<>();
        int dynamicEvidence = 0;
        int staticBridge = 0;
        for (TraceabilityEdge edge : edges) {
            if (edge.relation() == Relation.VERIFIED_BY) {
                reqWithTest.add(edge.source());
                reqToTc.computeIfAbsent(edge.source(), k -> new HashSet<>()).add(edge.target());
            }
            if (edge.relation() == Relation.COVERS) {
                tcWithCode.add(edge.source());
                tcToCode.computeIfAbsent(edge.source(), k -> new HashSet<>()).add(edge.target());
            }
            if (edge.evidenceType() == EvidenceType.EXECUTION_TRACE || edge.evidenceType() == EvidenceType.COVERAGE) {
                dynamicEvidence++;
            }
            if (edge.callEvidence() == CallEvidence.STATIC_BRIDGED) {
                staticBridge++;
            }
        }
        reqToTc.forEach((req, tcs) -> {
            if (tcs.stream().anyMatch(tc -> !tcToCode.getOrDefault(tc, Set.of()).isEmpty())) {
                completeReq.add(req);
            }
        });
        long codeCount = nodes.stream().filter(node -> node.kind().name().startsWith("CODE_")).count();
        long staticCount = nodes.stream().filter(node -> node.evidenceState() == EvidenceState.STATIC || node.evidenceState() == EvidenceState.BOTH).count();
        double rate = criteria.isEmpty() ? 0 : completeReq.size() * 1.0 / criteria.size();
        boolean clipped = warnings.stream().anyMatch(item -> item.contains("裁剪"));
        return new TraceabilitySummary(criteria.size(), testcases.size(), (int) codeCount, completeReq.size(), rate,
                (int) staticCount, dynamicNodes.size(), criteria.size() - reqWithTest.size(),
                testcases.size() - tcWithCode.size(), dynamicEvidence, staticBridge, clipped);
    }

    private List<CodeTreeNode> buildCodeTree(CodeIndex index, Set<String> visibleNodeIds, Set<String> dynamicNodes) {
        Map<String, MutableTreeNode> dirs = new LinkedHashMap<>();
        Map<String, MutableTreeNode> roots = new LinkedHashMap<>();
        for (TraceabilityNode node : index.nodes.values()) {
            if (node.kind() != NodeKind.CODE_FILE || (!visibleNodeIds.isEmpty() && !visibleNodeIds.contains(node.id()))) {
                continue;
            }
            String path = node.locator();
            String[] parts = path.split("/");
            String parentKey = "";
            Map<String, MutableTreeNode> currentLevel = roots;
            for (int i = 0; i < parts.length - 1; i++) {
                String key = parentKey.isEmpty() ? parts[i] : parentKey + "/" + parts[i];
                String label = parts[i];
                String language = node.language();
                MutableTreeNode dir = dirs.computeIfAbsent(key, k -> new MutableTreeNode(k, CodeTreeKind.DIRECTORY, label, key, null, language));
                currentLevel.putIfAbsent(key, dir);
                currentLevel = dir.children;
                parentKey = key;
            }
            MutableTreeNode file = new MutableTreeNode(node.id(), CodeTreeKind.FILE, node.label(), path, parentKey, node.language());
            file.evidenceState = dynamicNodes.contains(node.id()) ? EvidenceState.BOTH : node.evidenceState();
            file.coverage = node.coverage();
            currentLevel.put(node.id(), file);
            attachCodeChildren(index, file, node.id(), dynamicNodes);
        }
        return roots.values().stream().map(MutableTreeNode::toPayload).toList();
    }

    private void attachCodeChildren(CodeIndex index, MutableTreeNode parent, String parentId, Set<String> dynamicNodes) {
        index.nodes.values().stream()
                .filter(node -> Objects.equals(parentId, node.parentId()))
                .sorted(Comparator.comparing(TraceabilityNode::label))
                .forEach(node -> {
                    CodeTreeKind kind = node.kind() == NodeKind.CODE_CLASS ? CodeTreeKind.CLASS : CodeTreeKind.METHOD;
                    MutableTreeNode child = new MutableTreeNode(node.id(), kind, node.label(), node.locator(), parent.id, node.language());
                    child.evidenceState = dynamicNodes.contains(node.id()) ? EvidenceState.BOTH : node.evidenceState();
                    child.coverage = node.coverage();
                    parent.children.put(node.id(), child);
                    attachCodeChildren(index, child, node.id(), dynamicNodes);
                });
    }

    private CoverageSummary coverageSummary(ClassCoverageIndex index) {
        return new CoverageSummary(index.getCoveredLines(), index.getTotalLines(), index.getLineRate(),
                index.getCoveredBranchTargets(), index.getTotalBranchTargets(), index.getBranchRate());
    }

    private Map<String, Object> coverageMetadata(ClassCoverageIndex index) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        Set<Integer> totalLines = new LinkedHashSet<>();
        Set<Integer> coveredLines = new LinkedHashSet<>();
        Set<Integer> partialBranchLines = new LinkedHashSet<>();
        if (index.getTotalLineNumbers() != null) totalLines.addAll(index.getTotalLineNumbers());
        if (index.getCoveredLineNumbers() != null) coveredLines.addAll(index.getCoveredLineNumbers());
        if (index.getMethods() != null) {
            for (ClassCoverageIndex.MethodCoverageDetail method : index.getMethods()) {
                if (method == null) continue;
                if (method.getTotalLineNumbers() != null) totalLines.addAll(method.getTotalLineNumbers());
                if (method.getCoveredLineNumbers() != null) coveredLines.addAll(method.getCoveredLineNumbers());
                partialBranchLines.addAll(partialBranchLines(method));
            }
        }
        metadata.put("coverageTotalLines", new ArrayList<>(totalLines));
        metadata.put("coverageCoveredLines", new ArrayList<>(coveredLines));
        metadata.put("coveragePartialBranchLines", new ArrayList<>(partialBranchLines));
        return metadata;
    }

    private Set<Integer> partialBranchLines(ClassCoverageIndex.MethodCoverageDetail method) {
        Set<Integer> result = new LinkedHashSet<>();
        if (method == null || method.getTotalBranchTargetProbeMap() == null) return result;
        Map<String, List<Integer>> covered = method.getCoveredBranchTargetProbeMap();
        for (Map.Entry<String, List<Integer>> entry : method.getTotalBranchTargetProbeMap().entrySet()) {
            int separator = entry.getKey().indexOf(':');
            if (separator <= 0) continue;
            int total = entry.getValue() == null ? 0 : entry.getValue().size();
            List<Integer> coveredTargets = covered == null ? null : covered.get(entry.getKey());
            int coveredCount = coveredTargets == null ? 0 : coveredTargets.size();
            if (coveredCount > 0 && coveredCount < total) {
                try {
                    result.add(Integer.parseInt(entry.getKey().substring(0, separator)));
                } catch (NumberFormatException ignored) {
                    // Ignore malformed branch keys from third-party report formats.
                }
            }
        }
        return result;
    }

    private CoverageSummary mergeCoverage(CoverageSummary current, CoverageSummary incoming) {
        if (current == null) return incoming;
        int coveredLines = integer(current.coveredLines()) + integer(incoming.coveredLines());
        int totalLines = integer(current.totalLines()) + integer(incoming.totalLines());
        int coveredBranches = integer(current.coveredBranches()) + integer(incoming.coveredBranches());
        int totalBranches = integer(current.totalBranches()) + integer(incoming.totalBranches());
        return new CoverageSummary(coveredLines, totalLines, rate(coveredLines, totalLines),
                coveredBranches, totalBranches, rate(coveredBranches, totalBranches));
    }

    private int integer(Integer value) {
        return value == null ? 0 : value;
    }

    private Double rate(int covered, int total) {
        return total == 0 ? null : Math.round((double) covered / total * 10000) / 10000.0;
    }

    private String sourceSnippet(String source) {
        return value(source);
    }

    private String resolveTraceNode(String type, String id, CodeIndex codeIndex) {
        if (!StringUtils.hasText(type) || !StringUtils.hasText(id)) {
            return null;
        }
        String normalized = type.trim().toUpperCase(Locale.ROOT);
        if ("AC".equals(normalized) || "REQUIREMENT".equals(normalized)) {
            return reqId(id);
        }
        if ("TESTCASE".equals(normalized)) {
            return tcId(id);
        }
        if (normalized.contains("SOURCE") || normalized.contains("CODE") || "METHOD".equals(normalized) || "CLASS".equals(normalized) || "FILE".equals(normalized)) {
            return codeIndex.resolve(id);
        }
        return null;
    }

    private NormalizedRelation normalizePair(String source, String target) {
        if (source.startsWith("req:") && target.startsWith("tc:")) return new NormalizedRelation(source, target, Relation.VERIFIED_BY);
        if (source.startsWith("tc:") && target.startsWith("req:")) return new NormalizedRelation(target, source, Relation.VERIFIED_BY);
        if (source.startsWith("tc:") && target.startsWith("code:")) return new NormalizedRelation(source, target, Relation.COVERS);
        if (source.startsWith("code:") && target.startsWith("tc:")) return new NormalizedRelation(target, source, Relation.COVERS);
        if (source.startsWith("req:") && target.startsWith("code:")) return new NormalizedRelation(source, target, Relation.IMPLEMENTED_BY);
        if (source.startsWith("code:") && target.startsWith("req:")) return new NormalizedRelation(target, source, Relation.IMPLEMENTED_BY);
        if (source.startsWith("code:") && target.startsWith("code:")) return new NormalizedRelation(source, target, Relation.CALLS);
        return null;
    }

    private EvidenceType evidenceType(TraceLink link) {
        String text = (value(link.generationMethod()) + " " + value(link.relationType()) + " " + link.evidence()).toUpperCase(Locale.ROOT);
        if (text.contains("COVERAGE")) return EvidenceType.COVERAGE;
        if (text.contains("EXECUTION") || text.contains("RUNTIME") || text.contains("TRACE")) return EvidenceType.EXECUTION_TRACE;
        if (text.contains("STATIC")) return EvidenceType.STATIC_ANALYSIS;
        if (text.contains("DOCUMENT") || text.contains("MANUAL")) return EvidenceType.DOCUMENT;
        if (text.contains("DERIVED")) return EvidenceType.DERIVED;
        return EvidenceType.AI;
    }

    private EdgeEvidence edgeEvidence(String assetId, Map<String, Object> evidence, String reason) {
        if (evidence == null) {
            return new EdgeEvidence(assetId, null, null, null, null, reason, Map.of());
        }
        return new EdgeEvidence(assetId, stringValue(evidence.get("traceId")), stringValue(evidence.get("caseName")),
                stringValue(firstPresent(evidence, "locator", "file", "path")), intValue(firstPresent(evidence, "line", "lineNumber")),
                reason, evidence);
    }

    private JsonNode firstField(JsonNode node, String... names) {
        for (String name : names) {
            if (node.has(name)) {
                return node.get(name);
            }
        }
        return null;
    }

    private Object firstPresent(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
    }

    private List<SourceUnit> splitSourceUnits(String defaultName, String content) {
        List<SourceUnit> units = new ArrayList<>();
        // Git snapshots contain a SOURCE_FILE manifest followed by FILE content
        // markers. Prefer real content markers so the manifest cannot create
        // empty nodes that shadow the actual class and method nodes.
        Matcher matcher = CONTENT_FILE_PATTERN.matcher(content);
        if (!matcher.find()) {
            matcher = SOURCE_FILE_PATTERN.matcher(content);
        } else {
            matcher.reset();
        }
        int lastStart = -1;
        String lastPath = null;
        while (matcher.find()) {
            if (lastPath != null) {
                units.add(new SourceUnit(lastPath, sourceUnitContent(content, lastStart, matcher.start())));
            }
            lastPath = matcher.group(1).trim();
            lastStart = skipLineBreak(content, matcher.end());
        }
        if (lastPath != null) {
            units.add(new SourceUnit(lastPath, sourceUnitContent(content, lastStart, content.length())));
        }
        if (units.isEmpty()) {
            units.add(new SourceUnit(StringUtils.hasText(defaultName) ? defaultName : "ImportedSource.java", content));
        }
        return units;
    }

    /**
     * The marker line owns the line break immediately after it. Do not expose
     * that separator as a blank first source line.
     */
    private String sourceUnitContent(String content, int start, int end) {
        if (content == null || start >= end) return "";
        return content.substring(start, end);
    }

    private int skipLineBreak(String content, int index) {
        if (content == null || index >= content.length()) return index;
        if (content.startsWith("\r\n", index)) return index + 2;
        if (content.charAt(index) == '\n' || content.charAt(index) == '\r') return index + 1;
        return index;
    }

    private String assetContent(AssetSnapshot asset) {
        if (StringUtils.hasText(asset.storageKey())) {
            String loaded = assetContentStore.load(asset.storageKey());
            if (StringUtils.hasText(loaded)) {
                return loaded;
            }
        }
        return firstText(asset.content(), asset.contentPreview(), "");
    }

    private String extractClassName(String path, String source) {
        Matcher matcher = CLASS_PATTERN.matcher(source);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return normalizer.simpleFileName(path);
    }

    private List<ParsedSourceClass> parseJavaSource(String path, String source) {
        if (!CodeSymbolNormalizer.DEFAULT_LANGUAGE.equals(languageFromPath(path)) || !StringUtils.hasText(source)) {
            return List.of();
        }
        ParseResult<CompilationUnit> result = JAVA_PARSER.parse(source);
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            return List.of();
        }
        String packageName = result.getResult().get().getPackageDeclaration()
                .map(item -> item.getName().asString())
                .orElse("");
        List<ParsedSourceClass> classes = new ArrayList<>();
        for (TypeDeclaration<?> type : result.getResult().get().findAll(TypeDeclaration.class)) {
            if (!(type instanceof ClassOrInterfaceDeclaration || type instanceof EnumDeclaration || type instanceof RecordDeclaration)) {
                continue;
            }
            String className = qualifiedTypeName(packageName, type);
            List<ParsedSourceMethod> methods = new ArrayList<>();
            for (CallableDeclaration<?> callable : type.getMembers().stream()
                    .filter(member -> member instanceof MethodDeclaration || member instanceof ConstructorDeclaration)
                    .map(member -> (CallableDeclaration<?>) member)
                    .toList()) {
                callable.getRange().ifPresent(range -> methods.add(parsedMethod(callable, range.begin.line)));
            }
            classes.add(new ParsedSourceClass(className, methods));
        }
        return classes;
    }

    private ParsedSourceMethod parsedMethod(CallableDeclaration<?> callable, int line) {
        String methodName = callable.getNameAsString();
        String signature = callable.getSignature().asString();
        String descriptor = "(" + callable.getParameters().stream()
                .map(parameter -> parameter.getType().asString())
                .map(value -> value.replaceAll("\\s+", ""))
                .collect(Collectors.joining(",")) + ")";
        String body = callable.toString();
        boolean staticMethod = callable instanceof MethodDeclaration method && method.isStatic();
        String visibility = callable.isPrivate() ? "PRIVATE" : callable.isProtected() ? "PROTECTED" : "PUBLIC";
        List<InvocationCandidate> invocations = callable.findAll(MethodCallExpr.class).stream()
                .map(call -> new InvocationCandidate(call.getNameAsString(), call.getArguments().size()))
                .toList();
        return new ParsedSourceMethod(methodName, descriptor, signature, line, visibility, staticMethod, body, invocations);
    }

    private String qualifiedTypeName(String packageName, TypeDeclaration<?> type) {
        List<String> names = new ArrayList<>();
        Node current = type;
        while (current instanceof TypeDeclaration<?> declaration) {
            names.add(0, declaration.getNameAsString());
            current = declaration.getParentNode().orElse(null);
        }
        String nested = String.join("$", names);
        return StringUtils.hasText(packageName) ? packageName + "." + nested : nested;
    }

    private List<InvocationCandidate> inferredInvocations(String body) {
        if (!StringUtils.hasText(body)) {
            return List.of();
        }
        List<InvocationCandidate> result = new ArrayList<>();
        Matcher matcher = Pattern.compile("(?<![A-Za-z0-9_$])([A-Za-z_$][\\w$]*)\\s*\\(").matcher(body);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!JAVA_CONTROL_KEYWORDS.contains(name)) {
                result.add(new InvocationCandidate(name, -1));
            }
        }
        return result;
    }

    private List<MethodSpan> collectMethodSpans(String source, String className) {
        if (!StringUtils.hasText(source)) {
            return List.of();
        }
        List<MethodSpan> spans = new ArrayList<>();
        Matcher matcher = METHOD_PATTERN.matcher(source);
        while (matcher.find()) {
            String methodName = matcher.group(1);
            int end = methodBodyEnd(source, matcher.end() - 1);
            String body = end > matcher.start() ? source.substring(matcher.start(), end) : "";
            spans.add(new MethodSpan(null, className, methodName, body, inferredInvocations(body)));
        }
        return spans;
    }

    private int methodBodyEnd(String source, int openBraceIndex) {
        if (!StringUtils.hasText(source) || openBraceIndex < 0 || openBraceIndex >= source.length()) {
            return -1;
        }
        int open = source.indexOf('{', openBraceIndex);
        if (open < 0) {
            return -1;
        }
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        char quote = 0;
        for (int i = open; i < source.length(); i++) {
            char current = source.charAt(i);
            if (quoted) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == quote) {
                    quoted = false;
                }
                continue;
            }
            if (current == '"' || current == '\'') {
                quoted = true;
                quote = current;
                continue;
            }
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
        }
        return source.length();
    }

    private int lineNumber(String source, int offset) {
        int line = 1;
        for (int i = 0; i < Math.min(offset, source.length()); i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private String languageFromPath(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".ts") || lower.endsWith(".tsx")) return "typescript";
        if (lower.endsWith(".js") || lower.endsWith(".jsx") || lower.endsWith(".vue")) return "javascript";
        if (lower.endsWith(".py")) return "python";
        if (lower.endsWith(".go")) return "go";
        return CodeSymbolNormalizer.DEFAULT_LANGUAGE;
    }

    private String simpleClassName(String className) {
        if (!StringUtils.hasText(className)) {
            return "Unknown";
        }
        String base = className.contains("$") ? className.substring(0, className.indexOf('$')) : className;
        int split = Math.max(base.lastIndexOf('.'), base.lastIndexOf('/'));
        return split >= 0 ? base.substring(split + 1) : base;
    }

    private Integer firstLine(StaticSourceMethodInfo method) {
        if (method == null || method.getMethodLineNumberMap() == null || method.getMethodLineNumberMap().isEmpty()) {
            return null;
        }
        return method.getMethodLineNumberMap().get(0);
    }

    private String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private String compactLabel(String a, String b, String fallback) {
        if (StringUtils.hasText(a) && StringUtils.hasText(b)) {
            return a + "/" + b;
        }
        return firstText(a, b, fallback);
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return "";
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private String reqId(String id) {
        return "req:" + id;
    }

    private String tcId(String id) {
        return "tc:" + id;
    }

    private String stableId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private record SourceUnit(String path, String content) {}
    private record ParsedSourceClass(String className, List<ParsedSourceMethod> methods) {}
    private record ParsedSourceMethod(String methodName, String descriptor, String signature, int line, String visibility,
                                      boolean staticMethod, String body, List<InvocationCandidate> invocations) {}
    private record InvocationCandidate(String name, int argumentCount) {}
    private Map<String, Object> callMetadata(CallPair pair) {
        Integer opcode = pair.opcode();
        if (opcode == null) {
            return Map.of();
        }
        String callKind = opcode == 184 ? "STATIC" : "NORMAL";
        return Map.of("opcode", opcode, "callKind", callKind);
    }

    private record PendingCall(String callerId, String calleeKey, String generationMethod, Integer opcode) {}
    private record CallPair(String source, String target, String assetId, String traceId, String caseName, Integer opcode) {
        CallPair(String source, String target, String assetId, String traceId, String caseName) {
            this(source, target, assetId, traceId, caseName, null);
        }
    }
    private record MethodSpan(String methodId, String className, String methodName, String body, List<InvocationCandidate> invocations) {}
    private record NormalizedRelation(String source, String target, Relation relation) {}
    private record DynamicEvidence(Set<String> coveredNodeIds, List<CallPair> callPairs) {
        static DynamicEvidence empty() {
            return new DynamicEvidence(Set.of(), List.of());
        }
    }
    private record GraphView(List<TraceabilityNode> nodes, List<TraceabilityEdge> edges, Set<String> nodeIds) {}

    private final class CodeIndex {
        private final Map<String, TraceabilityNode> nodes = new LinkedHashMap<>();
        private final Map<String, String> aliases = new LinkedHashMap<>();
        private final List<PendingCall> pendingStaticCalls = new ArrayList<>();
        private final List<CallPair> staticCallPairs = new ArrayList<>();
        private final List<MethodSpan> methodSpans = new ArrayList<>();
        private final List<CodeDependency> dependencies = new ArrayList<>();
        private final List<ControlFlowStep> controlFlows = new ArrayList<>();

        void putCodeNode(TraceabilityNode node) {
            TraceabilityNode existing = nodes.get(node.id());
            if (existing == null) {
                nodes.put(node.id(), node);
            } else {
                Map<String, Object> metadata = new LinkedHashMap<>(existing.metadata() == null ? Map.of() : existing.metadata());
                if (node.metadata() != null) metadata.putAll(node.metadata());
                nodes.put(node.id(), new TraceabilityNode(existing.id(), existing.kind(), existing.label(), existing.description(),
                        existing.locator(), existing.layer(), existing.language(), existing.symbol(), existing.parentId(),
                        existing.evidenceState(), existing.coverage(), metadata));
            }
            alias(node.id(), node.id());
            alias(node.symbol(), node.id());
            alias(node.locator(), node.id());
            alias(node.label(), node.id());
        }

        void applyCoverageSummary(String nodeId, CoverageSummary coverage, Map<String, Object> coverageMetadata) {
            TraceabilityNode node = nodes.get(nodeId);
            if (node == null || coverage == null) return;
            Map<String, Object> metadata = new LinkedHashMap<>(node.metadata() == null ? Map.of() : node.metadata());
            if (coverageMetadata != null) metadata.putAll(coverageMetadata);
            nodes.put(nodeId, new TraceabilityNode(node.id(), node.kind(), node.label(), node.description(),
                    node.locator(), node.layer(), node.language(), node.symbol(), node.parentId(),
                    node.evidenceState(), mergeCoverage(node.coverage(), coverage), metadata));
        }

        void applyCoverageSummaryWithParents(String nodeId, CoverageSummary coverage, Map<String, Object> coverageMetadata) {
            String currentId = nodeId;
            Set<String> visited = new HashSet<>();
            while (StringUtils.hasText(currentId) && visited.add(currentId)) {
                applyCoverageSummary(currentId, coverage, coverageMetadata);
                TraceabilityNode current = nodes.get(currentId);
                currentId = current == null ? null : current.parentId();
            }
        }

        void applyMethodCoverage(ClassCoverageIndex index) {
            if (index == null || index.getMethods() == null) return;
            for (ClassCoverageIndex.MethodCoverageDetail method : index.getMethods()) {
                if (method == null || !StringUtils.hasText(method.getMethodName())) continue;
                String methodId = resolveCoverageMethod(index, method);
                if (methodId == null) continue;
                CoverageSummary coverage = new CoverageSummary(method.getCoveredLines(), method.getTotalLines(), rate(method.getCoveredLines(), method.getTotalLines()),
                        method.getCoveredBranchTargets(), method.getTotalBranchTargets(), method.getBranchRate());
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("coverageTotalLines", method.getTotalLineNumbers() == null ? List.of() : method.getTotalLineNumbers());
                metadata.put("coverageCoveredLines", method.getCoveredLineNumbers() == null ? List.of() : method.getCoveredLineNumbers());
                metadata.put("coveragePartialBranchLines", new ArrayList<>(partialBranchLines(method)));
                applyCoverageSummary(methodId, coverage, metadata);
            }
        }

        String resolveCoverageMethod(ClassCoverageIndex index, ClassCoverageIndex.MethodCoverageDetail method) {
            String coveragePath = normalizer.normalizePath(index.getSourcePath());
            String coverageOwner = value(method.getClassName()).replace('/', '.').replace('$', '.').toLowerCase(Locale.ROOT);
            String methodName = method.getMethodName();
            int startLine = method.getStartLine();
            return nodes.values().stream()
                    .filter(node -> node.kind() == NodeKind.CODE_METHOD)
                    .filter(node -> methodName.equals(node.label()))
                    .filter(node -> {
                        String locator = normalizer.normalizePath(value(node.locator()).replaceFirst(":\\d+$", ""));
                        String owner = value(node.symbol()).replace('#', '.').replace('$', '.').toLowerCase(Locale.ROOT);
                        boolean pathMatches = StringUtils.hasText(coveragePath)
                                && (locator.endsWith(coveragePath) || coveragePath.endsWith(locator));
                        boolean ownerMatches = StringUtils.hasText(coverageOwner)
                                && (owner.startsWith(coverageOwner + ".") || owner.endsWith("." + coverageOwner + "."));
                        return pathMatches || ownerMatches;
                    })
                    .min(Comparator
                            .comparingInt((TraceabilityNode node) -> methodLineDistance(node, startLine))
                            .thenComparing(TraceabilityNode::id))
                    .filter(node -> startLine <= 0 || methodLineDistance(node, startLine) <= 2)
                    .map(TraceabilityNode::id)
                    .orElse(null);
        }

        int methodLineDistance(TraceabilityNode node, int expectedLine) {
            if (expectedLine <= 0 || node == null) return 0;
            Integer actualLine = node.metadata() == null ? null : metadataInteger(node.metadata().get("line"));
            if (actualLine == null) {
                Matcher matcher = Pattern.compile(":(\\d+)$").matcher(value(node.locator()));
                actualLine = matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
            }
            return actualLine == null ? Integer.MAX_VALUE : Math.abs(actualLine - expectedLine);
        }

        Integer metadataInteger(Object rawValue) {
            if (rawValue instanceof Number number) return number.intValue();
            try {
                String text = rawValue == null ? "" : String.valueOf(rawValue);
                return StringUtils.hasText(text) ? Integer.parseInt(text) : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        void alias(String alias, String id) {
            String key = normalizer.normalizeLookupKey(alias);
            if (StringUtils.hasText(key)) {
                aliases.putIfAbsent(key, id);
            }
        }

        String resolve(String value) {
            if (!StringUtils.hasText(value)) {
                return null;
            }
            if (nodes.containsKey(value)) {
                return value;
            }
            String direct = aliases.get(normalizer.normalizeLookupKey(value));
            if (direct != null) {
                return direct;
            }
            String normalized = normalizer.normalizeLookupKey(value);
            return aliases.entrySet().stream()
                    .filter(entry -> normalized.contains(entry.getKey()) || entry.getKey().contains(normalized))
                    .max(Comparator.comparingInt(entry -> entry.getKey().length()))
                    .map(Map.Entry::getValue)
                    .orElse(null);
        }

        String resolveCoverageNode(ClassCoverageIndex index) {
            if (index == null) return null;
            String direct = resolveCoverageContainer(firstText(index.getSourcePath(), index.getClassName(), index.getDisplayName()));
            if (direct != null) return direct;
            String sourcePath = normalizer.normalizePath(firstText(index.getSourcePath(), index.getClassName(), index.getDisplayName()));
            String simpleName = simpleClassName(firstText(index.getClassName(), index.getDisplayName(), sourcePath))
                    .replaceFirst("\\.(java|kt|kts|scala|groovy)$", "")
                    .toLowerCase(Locale.ROOT);
            return nodes.values().stream()
                    .filter(node -> node.kind() == NodeKind.CODE_FILE || node.kind() == NodeKind.CODE_CLASS)
                    .filter(node -> {
                        String locator = normalizer.normalizePath(firstText(node.locator(), node.symbol(), node.description()));
                        String label = value(node.label()).replaceFirst("\\.(java|kt|kts|scala|groovy)$", "").toLowerCase(Locale.ROOT);
                        String symbol = value(node.symbol()).replaceFirst("\\.(java|kt|kts|scala|groovy)$", "").toLowerCase(Locale.ROOT);
                        return (StringUtils.hasText(sourcePath) && (locator.endsWith(sourcePath) || sourcePath.endsWith(locator)))
                                || (StringUtils.hasText(simpleName) && (label.equals(simpleName) || symbol.endsWith("." + simpleName)));
                    })
                    .max(Comparator.comparingInt(node -> value(node.locator()).length()))
                    .map(TraceabilityNode::id)
                    .orElse(null);
        }

        private String resolveCoverageContainer(String value) {
            if (!StringUtils.hasText(value)) return null;
            if (nodes.containsKey(value) && isCoverageContainer(nodes.get(value))) return value;
            String normalized = normalizer.normalizeLookupKey(value);
            String direct = aliases.get(normalized);
            if (direct != null && isCoverageContainer(nodes.get(direct))) return direct;
            return aliases.entrySet().stream()
                    .filter(entry -> {
                        TraceabilityNode node = nodes.get(entry.getValue());
                        return isCoverageContainer(node)
                                && (normalized.contains(entry.getKey()) || entry.getKey().contains(normalized));
                    })
                    .max(Comparator.comparingInt(entry -> entry.getKey().length()))
                    .map(Map.Entry::getValue)
                    .orElse(null);
        }

        private boolean isCoverageContainer(TraceabilityNode node) {
            return node != null && (node.kind() == NodeKind.CODE_FILE || node.kind() == NodeKind.CODE_CLASS);
        }

        void resolvePendingCalls() {
            if (!staticCallPairs.isEmpty()) {
                return;
            }
            for (PendingCall pending : pendingStaticCalls) {
                String callee = resolve(pending.calleeKey());
                if (callee != null) {
                    staticCallPairs.add(new CallPair(pending.callerId(), callee, pending.generationMethod(), null, null, pending.opcode()));
                }
            }
        }

        int inferSourceCallPairs() {
            int before = pendingStaticCalls.size();
            Set<String> existing = new HashSet<>();
            pendingStaticCalls.forEach(call -> existing.add(call.callerId() + "->" + call.calleeKey()));
            Map<String, List<MethodSpan>> methodsByName = new LinkedHashMap<>();
            for (MethodSpan span : methodSpans) {
                if (StringUtils.hasText(span.methodId()) && StringUtils.hasText(span.methodName())) {
                    methodsByName.computeIfAbsent(span.methodName(), ignored -> new ArrayList<>()).add(span);
                }
            }
            for (MethodSpan caller : methodSpans) {
                if (!StringUtils.hasText(caller.methodId()) || caller.invocations() == null || caller.invocations().isEmpty()) {
                    continue;
                }
                for (InvocationCandidate invocation : caller.invocations()) {
                    for (MethodSpan callee : methodsByName.getOrDefault(invocation.name(), List.of())) {
                        if (invocation.argumentCount() >= 0 && !methodArityMatches(callee, invocation.argumentCount())) {
                            continue;
                        }
                        String key = caller.methodId() + "->" + callee.methodId();
                        if (existing.add(key)) {
                            pendingStaticCalls.add(new PendingCall(caller.methodId(), callee.methodId(), "SOURCE_CALL_ANALYSIS", null));
                        }
                    }
                }
            }
            return pendingStaticCalls.size() - before;
        }

        private boolean methodArityMatches(MethodSpan callee, int argumentCount) {
            TraceabilityNode node = nodes.get(callee.methodId());
            if (node == null || node.metadata() == null) {
                return true;
            }
            Object descriptor = node.metadata().get("descriptor");
            if (descriptor == null) {
                return true;
            }
            String value = String.valueOf(descriptor);
            int open = value.indexOf('(');
            int close = value.indexOf(')');
            if (open < 0 || close < open) {
                return true;
            }
            String args = value.substring(open + 1, close).trim();
            if (args.isEmpty()) {
                return argumentCount == 0;
            }
            return args.split(",").length == argumentCount;
        }
    }

    private static final class GraphBuilder {
        private final Map<String, TraceabilityNode> nodes = new LinkedHashMap<>();
        private final Map<String, TraceabilityEdge> edges = new LinkedHashMap<>();
        private final Set<String> dynamicNodes = new LinkedHashSet<>();

        void putNode(TraceabilityNode node) {
            nodes.putIfAbsent(node.id(), node);
        }

        void markDynamic(String nodeId) {
            if (nodeId != null) {
                dynamicNodes.add(nodeId);
                TraceabilityNode node = nodes.get(nodeId);
                if (node != null) {
                    EvidenceState state = node.evidenceState() == EvidenceState.STATIC || node.evidenceState() == EvidenceState.BOTH
                            ? EvidenceState.BOTH : EvidenceState.DYNAMIC;
                    nodes.put(nodeId, new TraceabilityNode(node.id(), node.kind(), node.label(), node.description(),
                            node.locator(), node.layer(), node.language(), node.symbol(), node.parentId(),
                            state, node.coverage(), node.metadata()));
                }
            }
        }

        void putEdge(TraceabilityEdge edge) {
            String key = edge.source() + "|" + edge.target() + "|" + edge.relation();
            TraceabilityEdge existing = edges.get(key);
            if (existing == null || rank(edge) > rank(existing)) {
                edges.put(key, edge);
            }
        }

        GraphView focus(String focusId, String direction, int depth, int maxNodes, int maxEdges, List<String> warnings) {
            if (!StringUtils.hasText(focusId) || !nodes.containsKey(focusId)) {
                return clip(new LinkedHashSet<>(nodes.keySet()), new ArrayList<>(edges.values()), maxNodes, maxEdges, warnings);
            }
            Set<String> visited = new LinkedHashSet<>();
            Queue<NodeDepth> queue = new ArrayDeque<>();
            for (String seedId : codeScopeIds(focusId)) {
                visited.add(seedId);
                queue.add(new NodeDepth(seedId, 0));
            }
            String dir = StringUtils.hasText(direction) ? direction.toUpperCase(Locale.ROOT) : "BOTH";
            while (!queue.isEmpty()) {
                NodeDepth current = queue.poll();
                if (current.depth() >= depth) {
                    continue;
                }
                for (TraceabilityEdge edge : edges.values()) {
                    boolean forward = ("BOTH".equals(dir) || "DOWNSTREAM".equals(dir)) && edge.source().equals(current.id());
                    boolean backward = ("BOTH".equals(dir) || "UPSTREAM".equals(dir)) && edge.target().equals(current.id());
                    String next = forward ? edge.target() : backward ? edge.source() : null;
                    if (next != null && visited.add(next)) {
                        queue.add(new NodeDepth(next, current.depth() + 1));
                    }
                }
            }
            List<TraceabilityEdge> scopedEdges = edges.values().stream()
                    .filter(edge -> visited.contains(edge.source()) && visited.contains(edge.target()))
                    .toList();
            return clip(visited, scopedEdges, maxNodes, maxEdges, warnings);
        }

        private Set<String> codeScopeIds(String focusId) {
            LinkedHashSet<String> scope = new LinkedHashSet<>();
            scope.add(focusId);
            TraceabilityNode focus = nodes.get(focusId);
            if (focus == null || !focus.kind().name().startsWith("CODE_")) {
                return scope;
            }
            boolean changed = true;
            while (changed) {
                changed = false;
                for (TraceabilityNode node : nodes.values()) {
                    if (node.parentId() != null && scope.contains(node.parentId()) && scope.add(node.id())) {
                        changed = true;
                    }
                }
            }
            return scope;
        }

        private GraphView clip(Set<String> ids, List<TraceabilityEdge> scopedEdges, int maxNodes, int maxEdges, List<String> warnings) {
            boolean clipped = false;
            LinkedHashSet<String> nodeIds = new LinkedHashSet<>(ids);
            if (nodeIds.size() > maxNodes) {
                nodeIds = nodeIds.stream().limit(maxNodes)
                        .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
                clipped = true;
            }
            LinkedHashSet<String> visibleNodeIds = nodeIds;
            List<TraceabilityEdge> edgeList = scopedEdges.stream()
                    .filter(edge -> visibleNodeIds.contains(edge.source()) && visibleNodeIds.contains(edge.target()))
                    .limit(maxEdges)
                    .toList();
            if (scopedEdges.size() > edgeList.size()) {
                clipped = true;
            }
            if (clipped) {
                warnings.add("追溯图超过展示上限，已按当前焦点裁剪");
            }
            return new GraphView(nodeIds.stream().map(nodes::get).filter(Objects::nonNull).toList(), edgeList, nodeIds);
        }

        private int rank(TraceabilityEdge edge) {
            int evidenceRank = switch (edge.evidenceType()) {
                case EXECUTION_TRACE -> 6;
                case COVERAGE -> 5;
                case DOCUMENT -> 4;
                case STATIC_ANALYSIS -> 3;
                case AI -> 2;
                case DERIVED -> 1;
            };
            int reviewRank = edge.reviewStatus() == VerificationModels.ReviewStatus.CONFIRMED ? 3
                    : edge.reviewStatus() == VerificationModels.ReviewStatus.REJECTED ? -3 : 0;
            return evidenceRank * 100 + reviewRank * 10 + (int) Math.round(edge.confidence() * 10);
        }

        private record NodeDepth(String id, int depth) {}
    }

    private static final class MutableTreeNode {
        private final String id;
        private final CodeTreeKind kind;
        private final String label;
        private final String path;
        private final String parentId;
        private final String language;
        private EvidenceState evidenceState = EvidenceState.STATIC;
        private CoverageSummary coverage;
        private final Map<String, MutableTreeNode> children = new LinkedHashMap<>();

        private MutableTreeNode(String id, CodeTreeKind kind, String label, String path, String parentId, String language) {
            this.id = id;
            this.kind = kind;
            this.label = label;
            this.path = path;
            this.parentId = parentId;
            this.language = language;
        }

        private CodeTreeNode toPayload() {
            return new CodeTreeNode(id, kind, label, path, parentId, language, evidenceState, coverage,
                    children.values().stream().map(MutableTreeNode::toPayload).toList());
        }
    }
}
