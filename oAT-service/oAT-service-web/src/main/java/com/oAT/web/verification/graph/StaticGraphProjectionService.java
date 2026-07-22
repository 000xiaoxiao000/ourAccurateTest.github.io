package com.oAT.web.verification.graph;

import com.oAT.web.esDao.StaticInfoRepository;
import com.oAT.web.esDao.entity.StaticSourceClassInfo;
import com.oAT.web.esDao.entity.StaticSourceInfo;
import com.oAT.web.esDao.entity.StaticSourceMethodInfo;
import com.oAT.web.verification.model.GraphModels;
import com.oAT.web.verification.model.GraphModels.EvidenceKind;
import com.oAT.web.verification.model.GraphModels.GraphEdgeType;
import com.oAT.web.verification.model.GraphModels.GraphNodeKind;
import com.oAT.web.verification.model.GraphModels.SnapshotKind;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class StaticGraphProjectionService {
    private final GraphRepository graphRepository;
    private final StaticInfoRepository staticInfoRepository;

    public StaticGraphProjectionService(GraphRepository graphRepository, StaticInfoRepository staticInfoRepository) {
        this.graphRepository = graphRepository;
        this.staticInfoRepository = staticInfoRepository;
    }

    public ProjectionResult project(String projectId, String baselineId, String appId, String repositoryUrl, String sourceCommit) {
        String repository = firstText(repositoryUrl, "app:" + appId);
        String commit = firstText(sourceCommit, "unversioned");
        List<StaticSourceInfo> classes = staticInfoRepository.findByAppId(appId);
        String inputHash = GraphModels.fingerprint(classes.stream()
                .map(StaticSourceInfo::getId).filter(id -> id != null)
                .sorted().reduce("", (a, b) -> a + "|" + b));
        GraphRepository.GraphSnapshot snapshot = new GraphRepository.GraphSnapshot(
                UUID.randomUUID().toString(), projectId, baselineId, repositoryUrl, sourceCommit, SnapshotKind.STATIC,
                "static-index-v1", inputHash, "READY", Map.of("appId", appId, "classCount", classes.size()));
        graphRepository.invalidateSnapshots(baselineId, SnapshotKind.STATIC);
        graphRepository.saveSnapshot(snapshot);

        Map<String, String> methodNodeIds = new LinkedHashMap<>();
        Map<String, String> methodIdsByOwnerName = new LinkedHashMap<>();
        int nodeCount = 0;
        for (StaticSourceInfo source : classes) {
            if (source.getClassInfo() == null || !StringUtils.hasText(source.getClassInfo().getClassName())) continue;
            String className = source.getClassInfo().getClassName();
            String path = pathForClass(className);
            GraphModels.SymbolIdentity typeSymbol = new GraphModels.SymbolIdentity(repository, commit, path, className, "", "", "");
            String typeNodeId = "type:" + typeSymbol.fingerprint();
            graphRepository.saveNode(new GraphRepository.GraphNode(typeNodeId, snapshot.id(), baselineId, projectId, GraphNodeKind.TYPE,
                    typeSymbol.stableId(), typeSymbol.logicalId(), path, className, GraphModels.fingerprint(value(source.getClassInfo().getSourceCode())), Map.of("appId", appId)));
            nodeCount++;
            Map<String, StaticSourceMethodInfo> methods = source.getClassInfo().getMethodMaps();
            if (methods == null) continue;
            for (Map.Entry<String, StaticSourceMethodInfo> entry : methods.entrySet()) {
                StaticSourceMethodInfo method = entry.getValue();
                String name = firstText(method == null ? null : method.getMethodName(), entry.getKey());
                String descriptor = method == null ? "" : value(method.getMethodDesc());
                GraphModels.SymbolIdentity symbol = new GraphModels.SymbolIdentity(repository, commit, path, className, name, descriptor, "");
                String methodNodeId = "method:" + symbol.fingerprint();
                methodNodeIds.put(className + "#" + name + descriptor, methodNodeId);
                methodIdsByOwnerName.put(className + "#" + name, methodNodeId);
                int line = firstLine(method);
                Map<String, Object> attributes = new LinkedHashMap<>();
                attributes.put("appId", appId);
                attributes.put("descriptor", descriptor);
                attributes.put("line", line);
                attributes.put("branchLines", method == null || method.getBranchLineNumberSet() == null ? List.of() : method.getBranchLineNumberSet());
                graphRepository.saveNode(new GraphRepository.GraphNode(methodNodeId, snapshot.id(), baselineId, projectId, GraphNodeKind.METHOD,
                        symbol.stableId(), symbol.logicalId(), line > 0 ? path + ":" + line : path, name, null, attributes));
                graphRepository.saveEdge(edge(snapshot, projectId, baselineId, typeNodeId, methodNodeId, GraphEdgeType.CONTAINS,
                        EvidenceKind.STATIC_RESOLVED, "E2", 1d, null, null, Map.of()));
                nodeCount++;
            }
        }
        int edgeCount = 0;
        for (StaticSourceInfo source : classes) {
            if (source.getClassInfo() == null || source.getClassInfo().getMethodMaps() == null) continue;
            String owner = source.getClassInfo().getClassName();
            for (Map.Entry<String, StaticSourceMethodInfo> entry : source.getClassInfo().getMethodMaps().entrySet()) {
                StaticSourceMethodInfo method = entry.getValue();
                String name = firstText(method == null ? null : method.getMethodName(), entry.getKey());
                String caller = methodNodeIds.get(owner + "#" + name + value(method == null ? null : method.getMethodDesc()));
                if (caller == null || method == null || method.getInvocations() == null) continue;
                for (StaticSourceMethodInfo.InvocationInfo invocation : method.getInvocations()) {
                    if (invocation == null || !StringUtils.hasText(invocation.getName())) continue;
                    String targetOwner = value(invocation.getOwner()).replace('/', '.');
                    boolean descriptorMatched = true;
                    String target = methodNodeIds.get(targetOwner + "#" + invocation.getName() + value(invocation.descriptorValue()));
                    if (target == null) {
                        target = methodIdsByOwnerName.get(targetOwner + "#" + invocation.getName());
                        descriptorMatched = false;
                    }
                    if (target == null) continue;
                    // Evidence grading combines target resolution with the bytecode invoke opcode:
                    // virtual/interface/dynamic calls are polymorphic dispatch candidates (STATIC_POSSIBLE),
                    // while static/special/exact-descriptor calls are bytecode-confirmed (STATIC_RESOLVED).
                    DispatchKind dispatch = dispatchKind(invocation.getOpcode());
                    EvidenceKind kind = (descriptorMatched && dispatch != DispatchKind.POLYMORPHIC)
                            ? EvidenceKind.STATIC_RESOLVED : EvidenceKind.STATIC_POSSIBLE;
                    Map<String, Object> attributes = new LinkedHashMap<>();
                    attributes.put("opcode", invocation.getOpcode() == null ? "" : invocation.getOpcode());
                    attributes.put("dispatch", dispatch.name());
                    attributes.put("descriptorMatched", descriptorMatched);
                    if (dispatch == DispatchKind.POLYMORPHIC) attributes.put("possibleDynamicDispatch", true);
                    graphRepository.saveEdge(edge(snapshot, projectId, baselineId, caller, target, GraphEdgeType.CALLS_STATIC,
                            kind, kind == EvidenceKind.STATIC_RESOLVED ? "E2" : "E1", kind == EvidenceKind.STATIC_RESOLVED ? .9d : .6d,
                            null, null, attributes));
                    edgeCount++;
                }
            }
        }
        // Second pass: class-level structural edges (EXTENDS, IMPLEMENTS, INJECTS) and
        // method-level field-access edges (READS, WRITES). These depend on typeNodeIds built above.
        Map<String, String> typeNodeIds = new LinkedHashMap<>();
        for (StaticSourceInfo source : classes) {
            if (source.getClassInfo() == null || !StringUtils.hasText(source.getClassInfo().getClassName())) continue;
            String className = source.getClassInfo().getClassName();
            String path = pathForClass(className);
            GraphModels.SymbolIdentity typeSymbol = new GraphModels.SymbolIdentity(repository, commit, path, className, "", "", "");
            typeNodeIds.put(className, "type:" + typeSymbol.fingerprint());
        }

        for (StaticSourceInfo source : classes) {
            StaticSourceClassInfo classInfo = source.getClassInfo();
            if (classInfo == null || !StringUtils.hasText(classInfo.getClassName())) continue;
            String className = classInfo.getClassName();
            String sourceTypeNodeId = typeNodeIds.get(className);
            if (sourceTypeNodeId == null) continue;

            // EXTENDS edge
            if (StringUtils.hasText(classInfo.getSuperName())
                    && !classInfo.getSuperName().startsWith("java.lang.Object")) {
                String targetTypeId = typeNodeIds.get(classInfo.getSuperName());
                if (targetTypeId != null) {
                    graphRepository.saveEdge(edge(snapshot, projectId, baselineId, sourceTypeNodeId, targetTypeId,
                            GraphEdgeType.EXTENDS, EvidenceKind.STATIC_RESOLVED, "E2", 1d, null, null, Map.of()));
                    edgeCount++;
                }
            }

            // IMPLEMENTS edges
            if (classInfo.getInterfaces() != null) {
                for (String iface : classInfo.getInterfaces()) {
                    String targetTypeId = typeNodeIds.get(iface);
                    if (targetTypeId != null) {
                        graphRepository.saveEdge(edge(snapshot, projectId, baselineId, sourceTypeNodeId, targetTypeId,
                                GraphEdgeType.IMPLEMENTS, EvidenceKind.STATIC_RESOLVED, "E2", 1d, null, null, Map.of()));
                        edgeCount++;
                    }
                }
            }

            // INJECTS edges: fields annotated with @Autowired / @Resource / @Inject
            if (classInfo.getFields() != null) {
                for (StaticSourceClassInfo.FieldInfo field : classInfo.getFields().values()) {
                    if (field == null || field.getAnnotations() == null) continue;
                    boolean isInjected = field.getAnnotations().stream().anyMatch(a ->
                            a.contains("Autowired") || a.contains("Resource") || a.contains("Inject"));
                    if (!isInjected) continue;
                    String refType = field.referenceTypeName();
                    if (refType == null) continue;
                    String targetTypeId = typeNodeIds.get(refType);
                    if (targetTypeId != null) {
                        Map<String, Object> attrs = new LinkedHashMap<>();
                        attrs.put("fieldName", field.getName());
                        attrs.put("annotations", field.getAnnotations());
                        graphRepository.saveEdge(edge(snapshot, projectId, baselineId, sourceTypeNodeId, targetTypeId,
                                GraphEdgeType.INJECTS, EvidenceKind.STATIC_RESOLVED, "E2", .95d, null, null, attrs));
                        edgeCount++;
                    }
                }
            }

            // READS / WRITES edges from field-access instructions inside methods
            if (classInfo.getMethodMaps() != null) {
                for (Map.Entry<String, StaticSourceMethodInfo> entry : classInfo.getMethodMaps().entrySet()) {
                    StaticSourceMethodInfo method = entry.getValue();
                    if (method == null || method.getFieldAccesses() == null) continue;
                    String methodName = firstText(method.getMethodName(), entry.getKey());
                    String callerMethodId = methodNodeIds.get(className + "#" + methodName + value(method.getMethodDesc()));
                    if (callerMethodId == null) continue;
                    for (StaticSourceMethodInfo.FieldAccessInfo fa : method.getFieldAccesses()) {
                        if (fa == null || !StringUtils.hasText(fa.getName())) continue;
                        String fieldOwner = value(fa.getOwner());
                        String targetOwnerTypeId = typeNodeIds.get(fieldOwner);
                        if (targetOwnerTypeId == null) continue;
                        GraphEdgeType accessType = fa.isWrite() ? GraphEdgeType.WRITES : GraphEdgeType.READS;
                        Map<String, Object> attrs = new LinkedHashMap<>();
                        attrs.put("fieldName", fa.getName());
                        attrs.put("fieldDescriptor", value(fa.getDescriptor()));
                        attrs.put("opcode", fa.getOpcode() == null ? "" : fa.getOpcode());
                        graphRepository.saveEdge(edge(snapshot, projectId, baselineId, callerMethodId, targetOwnerTypeId,
                                accessType, EvidenceKind.STATIC_RESOLVED, "E2", .9d, null, null, attrs));
                        edgeCount++;
                    }
                }
            }
        }

        return new ProjectionResult(snapshot.id(), nodeCount, edgeCount);
    }

    private GraphRepository.GraphEdge edge(GraphRepository.GraphSnapshot snapshot, String projectId, String baselineId, String source, String target,
                                           GraphEdgeType type, EvidenceKind kind, String level, double confidence, String executionId,
                                           String locator, Map<String, Object> attributes) {
        return new GraphRepository.GraphEdge(GraphModels.edgeId(snapshot.id(), source, target, type, kind, executionId), snapshot.id(), baselineId,
                projectId, source, target, type, kind, level, confidence, executionId, locator, attributes);
    }
    private enum DispatchKind { STATIC, SPECIAL, POLYMORPHIC, UNKNOWN }

    // ASM invoke opcodes: INVOKEVIRTUAL=182, INVOKESPECIAL=183, INVOKESTATIC=184, INVOKEINTERFACE=185, INVOKEDYNAMIC=186.
    // Virtual/interface/dynamic are polymorphic dispatch (runtime-resolved target); static/special are exact.
    private DispatchKind dispatchKind(Integer opcode) {
        if (opcode == null) return DispatchKind.UNKNOWN;
        return switch (opcode) {
            case 184 -> DispatchKind.STATIC;
            case 183 -> DispatchKind.SPECIAL;
            case 182, 185, 186 -> DispatchKind.POLYMORPHIC;
            default -> DispatchKind.UNKNOWN;
        };
    }

    private int firstLine(StaticSourceMethodInfo method) { return method == null || method.getMethodLineNumberMap() == null || method.getMethodLineNumberMap().isEmpty() ? 0 : method.getMethodLineNumberMap().get(0); }
    private String pathForClass(String className) { return className.replace('.', '/') + ".java"; }
    private String firstText(String first, String fallback) { return StringUtils.hasText(first) ? first : fallback; }
    private String value(String input) { return input == null ? "" : input; }
    public record ProjectionResult(String snapshotId, int nodeCount, int edgeCount) {}
}
