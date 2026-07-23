package com.oAT.web.verification.graph;

import com.oAT.web.verification.impact.ImpactModels.SymbolKind;
import com.oAT.web.verification.impact.ImpactModels.SymbolSnapshot;
import com.oAT.web.verification.impact.PolyglotLanguageAnalyzer;
import com.oAT.web.verification.model.GraphModels;
import com.oAT.web.verification.model.GraphModels.EvidenceKind;
import com.oAT.web.verification.model.GraphModels.GraphEdgeType;
import com.oAT.web.verification.model.GraphModels.GraphNodeKind;
import com.oAT.web.verification.model.GraphModels.SnapshotKind;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Projects a static code graph for non-Java languages (C/C++, Python, Go, JS/TS) from imported source
 * asset snapshots. Symbols and call sites come from {@link PolyglotLanguageAnalyzer}, the same structural
 * extractor used by Git impact analysis, so graph and impact stay consistent. All call edges are marked
 * {@code STATIC_POSSIBLE}: text/regex structural extraction must never claim bytecode-level certainty.
 */
@Service
public class PolyglotStaticGraphProjectionService {
    private static final String FILE_PREFIX = "// FILE:";
    private static final String SOURCE_FILE_PREFIX = "// SOURCE_FILE:";
    private static final String SOURCE_TREE_BEGIN = "// SOURCE_TREE_BEGIN";
    private static final String SOURCE_TREE_END = "// SOURCE_TREE_END";
    private static final String SNAPSHOT_ID_PREFIX = "// SNAPSHOT_ID:";

    private final GraphRepository graphRepository;
    private final PolyglotLanguageAnalyzer analyzer;

    public PolyglotStaticGraphProjectionService(GraphRepository graphRepository, PolyglotLanguageAnalyzer analyzer) {
        this.graphRepository = graphRepository;
        this.analyzer = analyzer;
    }

    public StaticGraphProjectionService.ProjectionResult project(String projectId, String baselineId, String appId,
                                                                 String repositoryUrl, String sourceCommit, String language,
                                                                 String sourceContent) {
        return project(projectId, baselineId, appId, repositoryUrl, sourceCommit, language, null, sourceContent);
    }

    public StaticGraphProjectionService.ProjectionResult project(String projectId, String baselineId, String appId,
                                                                 String repositoryUrl, String sourceCommit, String language,
                                                                 String fallbackPath, String sourceContent) {
        String repository = firstText(repositoryUrl, "app:" + appId);
        String commit = firstText(sourceCommit, "unversioned");
        List<SourceFile> files = splitFiles(sourceContent, fallbackPath, language);
        String inputHash = GraphModels.fingerprint(files.stream().map(SourceFile::path).sorted().reduce("", (a, b) -> a + "|" + b));
        GraphRepository.GraphSnapshot snapshot = new GraphRepository.GraphSnapshot(
                UUID.randomUUID().toString(), projectId, baselineId, repositoryUrl, sourceCommit, SnapshotKind.STATIC,
                "polyglot-static-v1", inputHash, "READY", Map.of("appId", appId, "language", value(language), "fileCount", files.size()));
        graphRepository.invalidateSnapshots(baselineId, SnapshotKind.STATIC);
        graphRepository.saveSnapshot(snapshot);

        Map<String, String> methodNodeIdBySignature = new LinkedHashMap<>();
        Map<String, List<String>> methodIdsBySimpleName = new LinkedHashMap<>();
        List<PendingMethod> pendingMethods = new ArrayList<>();
        int nodeCount = 0;
        for (SourceFile file : files) {
            String fileNodeId = "source-file:" + GraphModels.fingerprint(repository + "|" + commit + "|" + file.path());
            graphRepository.saveNode(new GraphRepository.GraphNode(fileNodeId, snapshot.id(), baselineId, projectId, GraphNodeKind.SOURCE_FILE,
                    repository + "@" + commit + ":" + file.path(), file.path(), file.path(), file.path(),
                    GraphModels.fingerprint(value(file.content())), Map.of("appId", appId, "language", value(language))));
            nodeCount++;
            if (!analyzer.supports(file.path())) continue;
            List<SymbolSnapshot> symbols = safeAnalyze(file.path(), file.content());
            List<SymbolSnapshot> types = symbols.stream().filter(symbol -> symbol.kind() == SymbolKind.TYPE)
                    .sorted((left, right) -> Integer.compare(startLine(left), startLine(right))).toList();
            Map<String, String> typeNodeIdByName = new LinkedHashMap<>();
            for (SymbolSnapshot type : types) {
                GraphModels.SymbolIdentity identity = new GraphModels.SymbolIdentity(repository, commit, file.path(), type.qualifiedName(), "", "", "");
                String typeNodeId = "type:" + identity.fingerprint();
                typeNodeIdByName.put(type.qualifiedName(), typeNodeId);
                graphRepository.saveNode(new GraphRepository.GraphNode(typeNodeId, snapshot.id(), baselineId, projectId, GraphNodeKind.TYPE,
                        identity.stableId(), identity.logicalId(), file.path(), type.qualifiedName(), GraphModels.fingerprint(value(type.snippet())),
                        Map.of("appId", appId, "language", value(language), "resolution", "APPROXIMATE")));
                nodeCount++;
            }
            for (SymbolSnapshot method : symbols) {
                if (method.kind() != SymbolKind.METHOD && method.kind() != SymbolKind.CONSTRUCTOR) continue;
                String owner = enclosingType(types, startLine(method), fileBaseName(file.path()));
                String descriptor = signatureParameters(method.signature());
                GraphModels.SymbolIdentity identity = new GraphModels.SymbolIdentity(repository, commit, file.path(), owner, method.qualifiedName(), descriptor, "");
                String methodNodeId = "method:" + identity.fingerprint();
                int line = startLine(method);
                Map<String, Object> attributes = new LinkedHashMap<>();
                attributes.put("appId", appId);
                attributes.put("language", value(language));
                attributes.put("descriptor", descriptor);
                attributes.put("line", line);
                attributes.put("resolution", "APPROXIMATE");
                graphRepository.saveNode(new GraphRepository.GraphNode(methodNodeId, snapshot.id(), baselineId, projectId, GraphNodeKind.METHOD,
                        identity.stableId(), identity.logicalId(), line > 0 ? file.path() + ":" + line : file.path(), method.qualifiedName(), null, attributes));
                nodeCount++;
                methodNodeIdBySignature.put(file.path() + "#" + owner + "#" + method.qualifiedName() + descriptor, methodNodeId);
                methodIdsBySimpleName.computeIfAbsent(method.qualifiedName(), ignored -> new ArrayList<>()).add(methodNodeId);
                String typeNodeId = typeNodeIdByName.get(owner);
                if (typeNodeId != null) {
                    graphRepository.saveEdge(edge(snapshot, projectId, baselineId, typeNodeId, methodNodeId, GraphEdgeType.CONTAINS,
                            EvidenceKind.STATIC_POSSIBLE, "E1", .6d, Map.of()));
                } else {
                    graphRepository.saveEdge(edge(snapshot, projectId, baselineId, fileNodeId, methodNodeId, GraphEdgeType.CONTAINS,
                            EvidenceKind.STATIC_POSSIBLE, "E1", .6d, Map.of()));
                }
                pendingMethods.add(new PendingMethod(methodNodeId, method.invokedNames()));
            }
        }
        int edgeCount = 0;
        for (PendingMethod caller : pendingMethods) {
            for (String invoked : caller.invokedNames()) {
                for (String targetId : methodIdsBySimpleName.getOrDefault(invoked, List.of())) {
                    if (targetId.equals(caller.nodeId())) continue;
                    graphRepository.saveEdge(edge(snapshot, projectId, baselineId, caller.nodeId(), targetId, GraphEdgeType.CALLS_STATIC,
                            EvidenceKind.STATIC_POSSIBLE, "E1", .55d, Map.of("callee", invoked)));
                    edgeCount++;
                }
            }
        }
        return new StaticGraphProjectionService.ProjectionResult(snapshot.id(), nodeCount, edgeCount);
    }

    private List<SymbolSnapshot> safeAnalyze(String path, String content) {
        if (!analyzer.supports(path) || !StringUtils.hasText(content)) return List.of();
        return analyzer.analyze(path, content);
    }

    private List<SourceFile> splitFiles(String content, String fallbackPath, String language) {
        List<SourceFile> files = new ArrayList<>();
        if (!StringUtils.hasText(content)) return files;
        List<String> manifestPaths = new ArrayList<>();
        String currentPath = null;
        StringBuilder current = new StringBuilder();
        boolean sawMarker = false;
        for (String line : content.split("\\R", -1)) {
            String trimmed = line.trim();
            if (trimmed.equals(SOURCE_TREE_BEGIN) || trimmed.equals(SOURCE_TREE_END)
                    || trimmed.startsWith(SNAPSHOT_ID_PREFIX)) {
                continue;
            }
            if (trimmed.startsWith(SOURCE_FILE_PREFIX)) {
                sawMarker = true;
                String path = trimmed.substring(SOURCE_FILE_PREFIX.length()).trim().replace('\\', '/');
                if (StringUtils.hasText(path)) manifestPaths.add(path);
                continue;
            }
            if (trimmed.startsWith(FILE_PREFIX)) {
                sawMarker = true;
                if (currentPath != null) files.add(new SourceFile(currentPath, current.toString()));
                currentPath = trimmed.substring(FILE_PREFIX.length()).trim().replace('\\', '/');
                current = new StringBuilder();
                continue;
            }
            if (currentPath != null) current.append(line).append('\n');
        }
        if (currentPath != null) files.add(new SourceFile(currentPath, current.toString()));
        if (files.isEmpty() && !manifestPaths.isEmpty()) {
            for (String path : manifestPaths) files.add(new SourceFile(path, ""));
        }
        if (!sawMarker && files.isEmpty()) {
            files.add(new SourceFile(fallbackSourcePath(fallbackPath, language), content));
        }
        return files;
    }

    private String fallbackSourcePath(String path, String language) {
        if (StringUtils.hasText(path) && analyzer.supports(path)) return path.replace('\\', '/');
        String extension = switch (value(language).trim().toUpperCase(java.util.Locale.ROOT)) {
            case "PYTHON" -> ".py";
            case "GO" -> ".go";
            case "CPP" -> ".cpp";
            default -> ".ts";
        };
        return "source" + extension;
    }

    private String enclosingType(List<SymbolSnapshot> types, int methodStart, String fallback) {
        String owner = fallback;
        for (SymbolSnapshot type : types) {
            if (startLine(type) <= methodStart) owner = type.qualifiedName();
            else break;
        }
        return StringUtils.hasText(owner) ? owner : fallback;
    }

    private int startLine(SymbolSnapshot symbol) {
        return symbol.range() == null ? 0 : symbol.range().startLine();
    }

    private String signatureParameters(String signature) {
        if (!StringUtils.hasText(signature)) return "";
        int open = signature.indexOf('(');
        int close = signature.lastIndexOf(')');
        return open >= 0 && close > open ? signature.substring(open + 1, close).trim() : "";
    }

    private String fileBaseName(String path) {
        if (!StringUtils.hasText(path)) return "file";
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private GraphRepository.GraphEdge edge(GraphRepository.GraphSnapshot snapshot, String projectId, String baselineId, String source,
                                           String target, GraphEdgeType type, EvidenceKind kind, String level, double confidence,
                                           Map<String, Object> attributes) {
        return new GraphRepository.GraphEdge(GraphModels.edgeId(snapshot.id(), source, target, type, kind, null), snapshot.id(),
                baselineId, projectId, source, target, type, kind, level, confidence, null, null, attributes);
    }

    private String firstText(String first, String fallback) { return StringUtils.hasText(first) ? first : fallback; }
    private String value(String input) { return input == null ? "" : input; }

    private record SourceFile(String path, String content) {}
    private record PendingMethod(String nodeId, List<String> invokedNames) {}
}
