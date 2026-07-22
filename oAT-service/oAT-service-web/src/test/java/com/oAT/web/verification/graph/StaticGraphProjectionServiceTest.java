package com.oAT.web.verification.graph;

import com.oAT.web.esDao.StaticInfoRepository;
import com.oAT.web.esDao.entity.StaticSourceClassInfo;
import com.oAT.web.esDao.entity.StaticSourceInfo;
import com.oAT.web.esDao.entity.StaticSourceMethodInfo;
import com.oAT.web.verification.model.GraphModels.EvidenceKind;
import com.oAT.web.verification.model.GraphModels.GraphEdgeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class StaticGraphProjectionServiceTest {

    private GraphRepository graphRepository;
    private StaticInfoRepository staticInfoRepository;
    private StaticGraphProjectionService service;

    private final List<GraphRepository.GraphEdge> savedEdges = new ArrayList<>();
    private final List<GraphRepository.GraphNode> savedNodes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        graphRepository = mock(GraphRepository.class);
        staticInfoRepository = mock(StaticInfoRepository.class);
        service = new StaticGraphProjectionService(graphRepository, staticInfoRepository);
        doNothing().when(graphRepository).invalidateSnapshots(anyString(), any());
        doNothing().when(graphRepository).saveSnapshot(any());
        doAnswer(inv -> { savedNodes.add(inv.getArgument(0)); return null; }).when(graphRepository).saveNode(any());
        doAnswer(inv -> { savedEdges.add(inv.getArgument(0)); return null; }).when(graphRepository).saveEdge(any());
    }

    @Test
    void invokestatic_with_descriptor_match_is_STATIC_RESOLVED() {
        when(staticInfoRepository.findByAppId("app")).thenReturn(
                twoClasses_callerCallee("com.example.Caller", "call", "(V)V",
                        "com.example.Target", "process", "(V)V", 184));
        service.project("proj", "baseline", "app", "http://repo", "abc123");
        GraphRepository.GraphEdge e = callsStaticEdges().stream().findFirst()
                .orElseThrow(() -> new AssertionError("no CALLS_STATIC edge"));
        assertEquals(EvidenceKind.STATIC_RESOLVED, e.evidenceKind());
        assertEquals("E2", e.evidenceLevel());
    }

    @Test
    void invokevirtual_is_STATIC_POSSIBLE_regardless_of_descriptor() {
        when(staticInfoRepository.findByAppId("app")).thenReturn(
                twoClasses_callerCallee("com.example.Caller", "call", "(V)V",
                        "com.example.Target", "process", "(V)V", 182));
        service.project("proj", "baseline", "app", "http://repo", "abc123");
        GraphRepository.GraphEdge e = callsStaticEdges().stream().findFirst()
                .orElseThrow(() -> new AssertionError("no CALLS_STATIC edge"));
        assertEquals(EvidenceKind.STATIC_POSSIBLE, e.evidenceKind());
        assertEquals("E1", e.evidenceLevel());
        assertTrue((Boolean) e.attributes().getOrDefault("possibleDynamicDispatch", false));
    }

    @Test
    void invokeinterface_is_STATIC_POSSIBLE() {
        when(staticInfoRepository.findByAppId("app")).thenReturn(
                twoClasses_callerCallee("com.example.Caller", "call", "(V)V",
                        "com.example.ITarget", "process", "(V)V", 185));
        service.project("proj", "baseline", "app", "http://repo", "abc123");
        assertTrue(callsStaticEdges().stream().anyMatch(e -> e.evidenceKind() == EvidenceKind.STATIC_POSSIBLE));
    }

    @Test
    void invokespecial_with_descriptor_match_is_STATIC_RESOLVED() {
        when(staticInfoRepository.findByAppId("app")).thenReturn(
                twoClasses_callerCallee("com.example.Caller", "call", "(V)V",
                        "com.example.Target", "process", "(V)V", 183));
        service.project("proj", "baseline", "app", "http://repo", "abc123");
        assertTrue(callsStaticEdges().stream().anyMatch(e -> e.evidenceKind() == EvidenceKind.STATIC_RESOLVED));
    }

    @Test
    void projects_EXTENDS_edge_for_non_Object_superclass() {
        when(staticInfoRepository.findByAppId("app")).thenReturn(
                twoClasses_inheritance("com.example.Child", "com.example.Parent", null));
        service.project("proj", "baseline", "app", "http://repo", "abc123");
        assertFalse(edgesOfType(GraphEdgeType.EXTENDS).isEmpty());
        assertEquals(EvidenceKind.STATIC_RESOLVED, edgesOfType(GraphEdgeType.EXTENDS).get(0).evidenceKind());
    }

    @Test
    void does_not_project_EXTENDS_for_java_lang_Object() {
        when(staticInfoRepository.findByAppId("app")).thenReturn(
                twoClasses_inheritance("com.example.Plain", "java.lang.Object", null));
        service.project("proj", "baseline", "app", "http://repo", "abc123");
        assertTrue(edgesOfType(GraphEdgeType.EXTENDS).isEmpty());
    }

    @Test
    void projects_IMPLEMENTS_edge_for_interface() {
        when(staticInfoRepository.findByAppId("app")).thenReturn(
                twoClasses_inheritance("com.example.Impl", null, "com.example.IService"));
        service.project("proj", "baseline", "app", "http://repo", "abc123");
        assertFalse(edgesOfType(GraphEdgeType.IMPLEMENTS).isEmpty());
        assertEquals(EvidenceKind.STATIC_RESOLVED, edgesOfType(GraphEdgeType.IMPLEMENTS).get(0).evidenceKind());
    }

    @Test
    void projects_INJECTS_edge_for_Autowired_field() {
        when(staticInfoRepository.findByAppId("app")).thenReturn(
                twoClasses_autowired("com.example.ServiceA", "com.example.ServiceB", "serviceB"));
        service.project("proj", "baseline", "app", "http://repo", "abc123");
        List<GraphRepository.GraphEdge> injects = edgesOfType(GraphEdgeType.INJECTS);
        assertFalse(injects.isEmpty());
        assertEquals(EvidenceKind.STATIC_RESOLVED, injects.get(0).evidenceKind());
        assertEquals("serviceB", injects.get(0).attributes().get("fieldName"));
    }

    @Test
    void projects_READS_and_WRITES_for_field_accesses() {
        when(staticInfoRepository.findByAppId("app")).thenReturn(
                twoClasses_fieldAccesses("com.example.Service", "com.example.Repo",
                        List.of(fa("com.example.Repo", "repo", "Lcom/example/Repo;", 180),
                                fa("com.example.Repo", "repo", "Lcom/example/Repo;", 181))));
        service.project("proj", "baseline", "app", "http://repo", "abc123");
        assertFalse(edgesOfType(GraphEdgeType.READS).isEmpty());
        assertFalse(edgesOfType(GraphEdgeType.WRITES).isEmpty());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private List<GraphRepository.GraphEdge> callsStaticEdges() { return edgesOfType(GraphEdgeType.CALLS_STATIC); }
    private List<GraphRepository.GraphEdge> edgesOfType(GraphEdgeType type) {
        return savedEdges.stream().filter(e -> e.type() == type).toList();
    }

    private List<StaticSourceInfo> twoClasses_callerCallee(String callerClass, String callerMethod, String callerDesc,
                                                            String calleeClass, String calleeName, String calleeDesc,
                                                            int opcode) {
        StaticSourceMethodInfo.InvocationInfo inv = new StaticSourceMethodInfo.InvocationInfo();
        inv.setOwner(calleeClass);
        inv.setName(calleeName);
        inv.setDescriptor(calleeDesc);
        inv.setOpcode(opcode);

        StaticSourceMethodInfo callerM = method(callerMethod, callerDesc);
        callerM.setInvocations(List.of(inv));

        StaticSourceClassInfo callerInfo = classInfo(callerClass, Map.of(callerMethod, callerM));
        StaticSourceClassInfo calleeInfo = classInfo(calleeClass, Map.of(calleeName, method(calleeName, calleeDesc)));

        return List.of(src(callerInfo), src(calleeInfo));
    }

    private List<StaticSourceInfo> twoClasses_inheritance(String child, String superName, String iface) {
        StaticSourceClassInfo childInfo = classInfo(child, Map.of());
        if (superName != null) childInfo.setSuperName(superName);
        if (iface != null) childInfo.setInterfaces(List.of(iface));

        List<StaticSourceInfo> result = new ArrayList<>();
        result.add(src(childInfo));
        if (superName != null && !superName.startsWith("java.lang.Object")) result.add(src(classInfo(superName, Map.of())));
        if (iface != null) result.add(src(classInfo(iface, Map.of())));
        return result;
    }

    private List<StaticSourceInfo> twoClasses_autowired(String ownerClass, String fieldType, String fieldName) {
        StaticSourceClassInfo.FieldInfo field = new StaticSourceClassInfo.FieldInfo();
        field.setName(fieldName);
        field.setDescriptor("L" + fieldType.replace('.', '/') + ";");
        field.setAnnotations(List.of("Lorg/springframework/beans/factory/annotation/Autowired;"));
        StaticSourceClassInfo ownerInfo = classInfo(ownerClass, Map.of());
        ownerInfo.setFields(Map.of(fieldName, field));
        return List.of(src(ownerInfo), src(classInfo(fieldType, Map.of())));
    }

    private List<StaticSourceInfo> twoClasses_fieldAccesses(String ownerClass, String fieldOwner,
                                                              List<StaticSourceMethodInfo.FieldAccessInfo> accesses) {
        StaticSourceMethodInfo m = method("doWork", "()V");
        m.setFieldAccesses(accesses);
        StaticSourceClassInfo ownerInfo = classInfo(ownerClass, new LinkedHashMap<>(Map.of("doWork", m)));
        return List.of(src(ownerInfo), src(classInfo(fieldOwner, Map.of())));
    }

    private StaticSourceMethodInfo.FieldAccessInfo fa(String owner, String name, String desc, int opcode) {
        StaticSourceMethodInfo.FieldAccessInfo f = new StaticSourceMethodInfo.FieldAccessInfo();
        f.setOwner(owner);
        f.setName(name);
        f.setDescriptor(desc);
        f.setOpcode(opcode);
        return f;
    }

    private StaticSourceMethodInfo method(String name, String desc) {
        StaticSourceMethodInfo m = new StaticSourceMethodInfo();
        m.setMethodName(name);
        m.setMethodDesc(desc);
        m.setMethodLineNumberMap(List.of(10));
        m.setInvocations(new ArrayList<>());
        return m;
    }

    private StaticSourceClassInfo classInfo(String className, Map<String, StaticSourceMethodInfo> methods) {
        StaticSourceClassInfo c = new StaticSourceClassInfo();
        c.setClassName(className);
        c.setMethodMaps(new LinkedHashMap<>(methods));
        return c;
    }

    private StaticSourceInfo src(StaticSourceClassInfo classInfo) {
        StaticSourceInfo s = new StaticSourceInfo(classInfo);
        s.setAppId("app");
        return s;
    }
}
