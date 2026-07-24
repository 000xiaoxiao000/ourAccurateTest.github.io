package com.oAT.web.api.map;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.oAT.web.persistence.entity.ClassCoverageIndex;
import com.oAT.web.coverage.universal.JacocoCoverageParser;
import com.oAT.web.coverage.universal.UniversalCoverageFile;
import com.oAT.web.service.AppService;
import com.oAT.web.verification.VerificationRepository;
import com.oAT.web.verification.model.VerificationModels;
import com.oAT.web.verification.model.VerificationModels.AssetType;
import com.oAT.web.verification.model.VerificationModels.Baseline;
import com.oAT.web.verification.model.VerificationModels.BaselineStatus;
import com.oAT.web.verification.model.VerificationModels.Freshness;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static com.oAT.web.api.map.TraceabilityMapPayloads.EvidenceState.STATIC;
import static com.oAT.web.api.map.TraceabilityMapPayloads.NodeKind.CODE_CLASS;
import static com.oAT.web.api.map.TraceabilityMapPayloads.NodeKind.CODE_FILE;
import static com.oAT.web.api.map.TraceabilityMapPayloads.NodeKind.CODE_METHOD;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TraceabilityMapServiceSourceParsingTest {

    @Test
    void buildsPreciseJavaCfgWithoutConnectingReturnToContinuation() throws Exception {
        TraceabilityMapService service = new TraceabilityMapService(null, null, null, new CodeSymbolNormalizer(), null, null, null);
        BlockStmt body = StaticJavaParser.parseBlock("""
                {
                  if (num3 > 0) {
                    str.append("branch");
                    log.info("branch");
                  }
                  return response;
                }
                """);
        Method builder = TraceabilityMapService.class.getDeclaredMethod(
                "buildJavaControlFlowGraph", String.class, String.class, BlockStmt.class);
        builder.setAccessible(true);

        TraceabilityMapPayloads.ControlFlowGraph graph =
                (TraceabilityMapPayloads.ControlFlowGraph) builder.invoke(service, "method:web3", "web3", body);

        assertThat(graph.parseStatus()).isEqualTo(TraceabilityMapPayloads.ControlFlowParseStatus.PRECISE);
        assertThat(graph.nodes())
                .extracting(TraceabilityMapPayloads.ControlFlowNode::type)
                .contains(TraceabilityMapPayloads.ControlFlowNodeType.DECISION,
                        TraceabilityMapPayloads.ControlFlowNodeType.RETURN,
                        TraceabilityMapPayloads.ControlFlowNodeType.END);
        assertThat(graph.edges())
                .extracting(TraceabilityMapPayloads.ControlFlowEdge::type)
                .contains(TraceabilityMapPayloads.ControlFlowEdgeType.TRUE,
                        TraceabilityMapPayloads.ControlFlowEdgeType.FALSE,
                        TraceabilityMapPayloads.ControlFlowEdgeType.RETURN);

        String returnNodeId = graph.nodes().stream()
                .filter(node -> node.type() == TraceabilityMapPayloads.ControlFlowNodeType.RETURN)
                .findFirst()
                .orElseThrow()
                .id();
        String endNodeId = graph.nodes().stream()
                .filter(node -> node.type() == TraceabilityMapPayloads.ControlFlowNodeType.END)
                .findFirst()
                .orElseThrow()
                .id();
        assertThat(graph.edges())
                .noneMatch(edge -> edge.source().equals(returnNodeId) && edge.target().equals(endNodeId));
    }

    @Test
    void buildsJavaTryCatchCfgWithoutHidingItAsPartialExpansion() throws Exception {
        TraceabilityMapService service = new TraceabilityMapService(null, null, null, new CodeSymbolNormalizer(), null, null, null);
        BlockStmt body = StaticJavaParser.parseBlock("""
                {
                  try {
                    int value = num1 / num2;
                    return value;
                  } catch (ArithmeticException ex) {
                    return 0;
                  }
                }
                """);
        Method builder = TraceabilityMapService.class.getDeclaredMethod(
                "buildJavaControlFlowGraph", String.class, String.class, BlockStmt.class);
        builder.setAccessible(true);

        TraceabilityMapPayloads.ControlFlowGraph graph =
                (TraceabilityMapPayloads.ControlFlowGraph) builder.invoke(service, "method:division", "division", body);

        assertThat(graph.parseStatus()).isEqualTo(TraceabilityMapPayloads.ControlFlowParseStatus.PRECISE);
        assertThat(graph.nodes()).extracting(TraceabilityMapPayloads.ControlFlowNode::type)
                .contains(TraceabilityMapPayloads.ControlFlowNodeType.TRY,
                        TraceabilityMapPayloads.ControlFlowNodeType.CATCH,
                        TraceabilityMapPayloads.ControlFlowNodeType.RETURN);
        assertThat(graph.edges()).extracting(TraceabilityMapPayloads.ControlFlowEdge::type)
                .contains(TraceabilityMapPayloads.ControlFlowEdgeType.EXCEPTION,
                        TraceabilityMapPayloads.ControlFlowEdgeType.RETURN);
        assertThat(graph.nodes()).noneMatch(node -> node.type() == TraceabilityMapPayloads.ControlFlowNodeType.UNKNOWN_BLOCK);
    }

    @Test
    void buildsJavaCommonStatementsWithoutMarkingThemUnknown() throws Exception {
        TraceabilityMapService service = new TraceabilityMapService(null, null, null, new CodeSymbolNormalizer(), null, null, null);
        BlockStmt body = StaticJavaParser.parseBlock("""
                {
                  synchronized (lock) {
                    assert num > 0;
                    label:
                    num++;
                  }
                  return num;
                }
                """);
        Method builder = TraceabilityMapService.class.getDeclaredMethod(
                "buildJavaControlFlowGraph", String.class, String.class, BlockStmt.class);
        builder.setAccessible(true);

        TraceabilityMapPayloads.ControlFlowGraph graph =
                (TraceabilityMapPayloads.ControlFlowGraph) builder.invoke(service, "method:setNum", "setNum", body);

        assertThat(graph.parseStatus()).isEqualTo(TraceabilityMapPayloads.ControlFlowParseStatus.PRECISE);
        assertThat(graph.nodes()).noneMatch(node -> node.type() == TraceabilityMapPayloads.ControlFlowNodeType.UNKNOWN_BLOCK);
    }

    @Test
    void buildsPreciseJavascriptCfgFromBabelWorker() throws Exception {
        TraceabilityMapService service = new TraceabilityMapService(null, null, null, new CodeSymbolNormalizer(), null, null, null);
        Class<?> codeIndexType = Arrays.stream(TraceabilityMapService.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("CodeIndex"))
                .findFirst()
                .orElseThrow();
        Constructor<?> constructor = codeIndexType.getDeclaredConstructor(TraceabilityMapService.class);
        constructor.setAccessible(true);
        Object codeIndex = constructor.newInstance(service);
        VerificationModels.AssetSnapshot asset = new VerificationModels.AssetSnapshot(
                "asset-js", "project", AssetType.SOURCE, VerificationModels.SourceType.FILE,
                null, null, null, "service.js", "hash", """
                function web3(num3) {
                    if (num3 > 0) {
                        return "yes"
                    }
                    return "no"
                }
                """, "INLINE", null, 0, null, Map.of(), Freshness.SNAPSHOT, "tester", LocalDateTime.now());
        Class<?> sourceUnitType = Arrays.stream(TraceabilityMapService.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("SourceUnit"))
                .findFirst()
                .orElseThrow();
        Constructor<?> sourceUnitConstructor = sourceUnitType.getDeclaredConstructor(String.class, String.class);
        sourceUnitConstructor.setAccessible(true);
        Object sourceUnit = sourceUnitConstructor.newInstance("service.js", asset.content());
        Method fallback = TraceabilityMapService.class.getDeclaredMethod(
                "addSourceAssetWithRegexFallback", codeIndexType, VerificationModels.AssetSnapshot.class,
                boolean.class, sourceUnitType, String.class);
        fallback.setAccessible(true);
        fallback.invoke(service, codeIndex, asset, true, sourceUnit, "code:javascript:service.js");

        Field graphsField = codeIndexType.getDeclaredField("controlFlowGraphs");
        graphsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<TraceabilityMapPayloads.ControlFlowGraph> graphs =
                (List<TraceabilityMapPayloads.ControlFlowGraph>) graphsField.get(codeIndex);

        assertThat(graphs).hasSize(1);
        TraceabilityMapPayloads.ControlFlowGraph graph = graphs.get(0);
        assertThat(graph.language()).isEqualTo("javascript");
        assertThat(graph.parseStatus()).isEqualTo(TraceabilityMapPayloads.ControlFlowParseStatus.PRECISE);
        assertThat(graph.nodes()).extracting(TraceabilityMapPayloads.ControlFlowNode::type)
                .contains(TraceabilityMapPayloads.ControlFlowNodeType.DECISION,
                        TraceabilityMapPayloads.ControlFlowNodeType.RETURN);
        assertThat(graph.edges()).extracting(TraceabilityMapPayloads.ControlFlowEdge::type)
                .contains(TraceabilityMapPayloads.ControlFlowEdgeType.TRUE,
                        TraceabilityMapPayloads.ControlFlowEdgeType.FALSE,
                        TraceabilityMapPayloads.ControlFlowEdgeType.RETURN);
    }

    @Test
    void appliesLineCoverageStateToCfgNodesWithoutInferringEdges() throws Exception {
        TraceabilityMapService service = new TraceabilityMapService(null, null, null, new CodeSymbolNormalizer(), null, null, null);
        Class<?> codeIndexType = Arrays.stream(TraceabilityMapService.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("CodeIndex"))
                .findFirst()
                .orElseThrow();
        Constructor<?> constructor = codeIndexType.getDeclaredConstructor(TraceabilityMapService.class);
        constructor.setAccessible(true);
        Object codeIndex = constructor.newInstance(service);
        Method putCodeNode = codeIndexType.getDeclaredMethod("putCodeNode", TraceabilityMapPayloads.TraceabilityNode.class);
        putCodeNode.setAccessible(true);

        String path = "src/service.js";
        String fileId = "code:javascript:" + path;
        String classId = fileId + "#service.js";
        String methodId = classId + ".web3(num3)";
        putCodeNode.invoke(codeIndex, new TraceabilityMapPayloads.TraceabilityNode(fileId, CODE_FILE, "service.js", path, path,
                "CODE", "javascript", path, null, STATIC, null, Map.of()));
        putCodeNode.invoke(codeIndex, new TraceabilityMapPayloads.TraceabilityNode(classId, CODE_CLASS, "service.js", path, path,
                "CODE", "javascript", "service.js", fileId, STATIC, null, Map.of()));
        putCodeNode.invoke(codeIndex, new TraceabilityMapPayloads.TraceabilityNode(methodId, CODE_METHOD, "web3", "web3(num3)", path + ":10",
                "CODE", "javascript", "service.js#web3", classId, STATIC, null,
                Map.of(
                        "coverageTotalLines", List.of(10, 11, 12),
                        "coverageCoveredLines", List.of(10, 12),
                        "coveragePartialBranchLines", List.of(11),
                        "coverageTotalBranchTargetProbeMap", Map.of("11:istanbul-if", List.of(0, 1)),
                        "coverageCoveredBranchTargetProbeMap", Map.of("11:istanbul-if", List.of(0)))));

        Field graphsField = codeIndexType.getDeclaredField("controlFlowGraphs");
        graphsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<TraceabilityMapPayloads.ControlFlowGraph> graphs =
                (List<TraceabilityMapPayloads.ControlFlowGraph>) graphsField.get(codeIndex);
        graphs.add(new TraceabilityMapPayloads.ControlFlowGraph(methodId, "web3", "javascript",
                TraceabilityMapPayloads.ControlFlowParseStatus.PRECISE, "test",
                List.of(
                        new TraceabilityMapPayloads.ControlFlowNode("n1", TraceabilityMapPayloads.ControlFlowNodeType.START, "开始", "", 10, 0, 0, true, "UNKNOWN"),
                        new TraceabilityMapPayloads.ControlFlowNode("n2", TraceabilityMapPayloads.ControlFlowNodeType.DECISION, "判断", "num3 > 0", 11, 1, 0, true, "UNKNOWN"),
                        new TraceabilityMapPayloads.ControlFlowNode("n3", TraceabilityMapPayloads.ControlFlowNodeType.RETURN, "返回", "response", 12, 2, 0, true, "UNKNOWN"),
                        new TraceabilityMapPayloads.ControlFlowNode("n4", TraceabilityMapPayloads.ControlFlowNodeType.RETURN, "返回", "fallback", 13, 3, 0, true, "UNKNOWN")),
                List.of(
                        new TraceabilityMapPayloads.ControlFlowEdge("e1", "n1", "n2", TraceabilityMapPayloads.ControlFlowEdgeType.NEXT, "下一步", true, "UNKNOWN"),
                        new TraceabilityMapPayloads.ControlFlowEdge("e2", "n2", "n3", TraceabilityMapPayloads.ControlFlowEdgeType.TRUE, "是", true, "UNKNOWN"),
                        new TraceabilityMapPayloads.ControlFlowEdge("e3", "n2", "n4", TraceabilityMapPayloads.ControlFlowEdgeType.FALSE, "否", true, "UNKNOWN")),
                "n1", List.of("n3")));

        Method applyControlFlowCoverage = codeIndexType.getDeclaredMethod("applyControlFlowCoverage");
        applyControlFlowCoverage.setAccessible(true);
        applyControlFlowCoverage.invoke(codeIndex);

        TraceabilityMapPayloads.ControlFlowGraph graph = graphs.get(0);
        assertThat(graph.nodes()).extracting(TraceabilityMapPayloads.ControlFlowNode::coverageState)
                .containsExactly("COVERED", "PARTIAL", "COVERED", "UNKNOWN");
        assertThat(graph.edges()).extracting(TraceabilityMapPayloads.ControlFlowEdge::coverageState)
                .containsExactly("UNKNOWN", "PARTIAL", "PARTIAL");
    }

    @Test
    void marksMissedCfgNodesAndBranchEdgesAsUncoveredFromJacocoLines() throws Exception {
        TraceabilityMapService service = new TraceabilityMapService(null, null, null, new CodeSymbolNormalizer(), null, null, null);
        Class<?> codeIndexType = Arrays.stream(TraceabilityMapService.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("CodeIndex"))
                .findFirst()
                .orElseThrow();
        Constructor<?> constructor = codeIndexType.getDeclaredConstructor(TraceabilityMapService.class);
        constructor.setAccessible(true);
        Object codeIndex = constructor.newInstance(service);
        Method putCodeNode = codeIndexType.getDeclaredMethod("putCodeNode", TraceabilityMapPayloads.TraceabilityNode.class);
        putCodeNode.setAccessible(true);

        String path = "src/Web3Controller.java";
        String fileId = "code:java:" + path;
        String classId = fileId + "#web3Server.controller.Web3Controller";
        String methodId = classId + ".setNum(int)";
        putCodeNode.invoke(codeIndex, new TraceabilityMapPayloads.TraceabilityNode(fileId, CODE_FILE, "Web3Controller.java", path, path,
                "CODE", "java", path, null, STATIC, null, Map.of()));
        putCodeNode.invoke(codeIndex, new TraceabilityMapPayloads.TraceabilityNode(classId, CODE_CLASS, "Web3Controller", path, path,
                "CODE", "java", "web3Server.controller.Web3Controller", fileId, STATIC, null, Map.of()));
        putCodeNode.invoke(codeIndex, new TraceabilityMapPayloads.TraceabilityNode(methodId, CODE_METHOD, "setNum", "setNum(int)", path + ":524",
                "CODE", "java", "web3Server.controller.Web3Controller#setNum", classId, STATIC, null,
                Map.of(
                        "coverageTotalLines", List.of(524, 525, 526),
                        "coverageCoveredLines", List.of(),
                        "coveragePartialBranchLines", List.of(),
                        "coverageTotalBranchTargetProbeMap", Map.of("524:jacoco", List.of(0, 1, 2, 3)),
                        "coverageCoveredBranchTargetProbeMap", Map.of())));

        Field graphsField = codeIndexType.getDeclaredField("controlFlowGraphs");
        graphsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<TraceabilityMapPayloads.ControlFlowGraph> graphs =
                (List<TraceabilityMapPayloads.ControlFlowGraph>) graphsField.get(codeIndex);
        graphs.add(new TraceabilityMapPayloads.ControlFlowGraph(methodId, "setNum", "java",
                TraceabilityMapPayloads.ControlFlowParseStatus.PRECISE, "test",
                List.of(
                        new TraceabilityMapPayloads.ControlFlowNode("n1", TraceabilityMapPayloads.ControlFlowNodeType.START, "开始", "", 524, 0, 0, true, "UNKNOWN"),
                        new TraceabilityMapPayloads.ControlFlowNode("n2", TraceabilityMapPayloads.ControlFlowNodeType.DECISION, "判断", "num > 0", 524, 1, 0, true, "UNKNOWN"),
                        new TraceabilityMapPayloads.ControlFlowNode("n3", TraceabilityMapPayloads.ControlFlowNodeType.RETURN, "返回", "1", 525, 2, 0, true, "UNKNOWN"),
                        new TraceabilityMapPayloads.ControlFlowNode("n4", TraceabilityMapPayloads.ControlFlowNodeType.RETURN, "返回", "0", 526, 3, 0, true, "UNKNOWN")),
                List.of(
                        new TraceabilityMapPayloads.ControlFlowEdge("e1", "n1", "n2", TraceabilityMapPayloads.ControlFlowEdgeType.NEXT, "下一步", true, "UNKNOWN"),
                        new TraceabilityMapPayloads.ControlFlowEdge("e2", "n2", "n3", TraceabilityMapPayloads.ControlFlowEdgeType.TRUE, "是", true, "UNKNOWN"),
                        new TraceabilityMapPayloads.ControlFlowEdge("e3", "n2", "n4", TraceabilityMapPayloads.ControlFlowEdgeType.FALSE, "否", true, "UNKNOWN")),
                "n1", List.of("n3", "n4")));

        Method applyControlFlowCoverage = codeIndexType.getDeclaredMethod("applyControlFlowCoverage");
        applyControlFlowCoverage.setAccessible(true);
        applyControlFlowCoverage.invoke(codeIndex);

        TraceabilityMapPayloads.ControlFlowGraph graph = graphs.get(0);
        assertThat(graph.nodes()).extracting(TraceabilityMapPayloads.ControlFlowNode::coverageState)
                .containsExactly("UNCOVERED", "UNCOVERED", "UNCOVERED", "UNCOVERED");
        assertThat(graph.edges()).extracting(TraceabilityMapPayloads.ControlFlowEdge::coverageState)
                .containsExactly("UNKNOWN", "UNCOVERED", "UNCOVERED");
    }

    @Test
    void buildsPreciseCppCfgFromClangWorker() throws Exception {
        TraceabilityMapService service = new TraceabilityMapService(null, null, null, new CodeSymbolNormalizer(), null, null, null);
        Class<?> codeIndexType = Arrays.stream(TraceabilityMapService.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("CodeIndex"))
                .findFirst()
                .orElseThrow();
        Constructor<?> constructor = codeIndexType.getDeclaredConstructor(TraceabilityMapService.class);
        constructor.setAccessible(true);
        Object codeIndex = constructor.newInstance(service);
        VerificationModels.AssetSnapshot asset = new VerificationModels.AssetSnapshot(
                "asset-cpp", "project", AssetType.SOURCE, VerificationModels.SourceType.FILE,
                null, null, null, "service.cpp", "hash", """
                int web3(int num3) {
                    if (num3 > 0) {
                        return 1;
                    }
                    return 0;
                }
                """, "INLINE", null, 0, null, Map.of(), Freshness.SNAPSHOT, "tester", LocalDateTime.now());
        Class<?> sourceUnitType = Arrays.stream(TraceabilityMapService.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("SourceUnit"))
                .findFirst()
                .orElseThrow();
        Constructor<?> sourceUnitConstructor = sourceUnitType.getDeclaredConstructor(String.class, String.class);
        sourceUnitConstructor.setAccessible(true);
        Object sourceUnit = sourceUnitConstructor.newInstance("service.cpp", asset.content());
        Method fallback = TraceabilityMapService.class.getDeclaredMethod(
                "addSourceAssetWithRegexFallback", codeIndexType, VerificationModels.AssetSnapshot.class,
                boolean.class, sourceUnitType, String.class);
        fallback.setAccessible(true);
        fallback.invoke(service, codeIndex, asset, true, sourceUnit, "code:cpp:service.cpp");

        Field graphsField = codeIndexType.getDeclaredField("controlFlowGraphs");
        graphsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<TraceabilityMapPayloads.ControlFlowGraph> graphs =
                (List<TraceabilityMapPayloads.ControlFlowGraph>) graphsField.get(codeIndex);

        assertThat(graphs).hasSize(1);
        TraceabilityMapPayloads.ControlFlowGraph graph = graphs.get(0);
        assertThat(graph.language()).isEqualTo("cpp");
        assertThat(graph.parseStatus()).isEqualTo(TraceabilityMapPayloads.ControlFlowParseStatus.PRECISE);
        assertThat(graph.nodes()).extracting(TraceabilityMapPayloads.ControlFlowNode::type)
                .contains(TraceabilityMapPayloads.ControlFlowNodeType.DECISION,
                        TraceabilityMapPayloads.ControlFlowNodeType.RETURN);
        assertThat(graph.edges()).extracting(TraceabilityMapPayloads.ControlFlowEdge::type)
                .contains(TraceabilityMapPayloads.ControlFlowEdgeType.TRUE,
                        TraceabilityMapPayloads.ControlFlowEdgeType.FALSE,
                        TraceabilityMapPayloads.ControlFlowEdgeType.RETURN);
    }

    @Test
    void buildsPreciseCCfgFromClangWorker() throws Exception {
        TraceabilityMapService service = new TraceabilityMapService(null, null, null, new CodeSymbolNormalizer(), null, null, null);
        Class<?> codeIndexType = Arrays.stream(TraceabilityMapService.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("CodeIndex"))
                .findFirst()
                .orElseThrow();
        Constructor<?> constructor = codeIndexType.getDeclaredConstructor(TraceabilityMapService.class);
        constructor.setAccessible(true);
        Object codeIndex = constructor.newInstance(service);
        VerificationModels.AssetSnapshot asset = new VerificationModels.AssetSnapshot(
                "asset-c", "project", AssetType.SOURCE, VerificationModels.SourceType.FILE,
                null, null, null, "service.c", "hash", """
                int web3(int num3) {
                    if (num3 > 0) {
                        return 1;
                    }
                    return 0;
                }
                """, "INLINE", null, 0, null, Map.of(), Freshness.SNAPSHOT, "tester", LocalDateTime.now());
        Class<?> sourceUnitType = Arrays.stream(TraceabilityMapService.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("SourceUnit"))
                .findFirst()
                .orElseThrow();
        Constructor<?> sourceUnitConstructor = sourceUnitType.getDeclaredConstructor(String.class, String.class);
        sourceUnitConstructor.setAccessible(true);
        Object sourceUnit = sourceUnitConstructor.newInstance("service.c", asset.content());
        Method fallback = TraceabilityMapService.class.getDeclaredMethod(
                "addSourceAssetWithRegexFallback", codeIndexType, VerificationModels.AssetSnapshot.class,
                boolean.class, sourceUnitType, String.class);
        fallback.setAccessible(true);
        fallback.invoke(service, codeIndex, asset, true, sourceUnit, "code:c:service.c");

        Field graphsField = codeIndexType.getDeclaredField("controlFlowGraphs");
        graphsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<TraceabilityMapPayloads.ControlFlowGraph> graphs =
                (List<TraceabilityMapPayloads.ControlFlowGraph>) graphsField.get(codeIndex);

        assertThat(graphs).hasSize(1);
        TraceabilityMapPayloads.ControlFlowGraph graph = graphs.get(0);
        assertThat(graph.language()).isEqualTo("c");
        assertThat(graph.parseStatus()).isEqualTo(TraceabilityMapPayloads.ControlFlowParseStatus.PRECISE);
        assertThat(graph.nodes()).extracting(TraceabilityMapPayloads.ControlFlowNode::type)
                .contains(TraceabilityMapPayloads.ControlFlowNodeType.DECISION,
                        TraceabilityMapPayloads.ControlFlowNodeType.RETURN);
        assertThat(graph.edges()).extracting(TraceabilityMapPayloads.ControlFlowEdge::type)
                .contains(TraceabilityMapPayloads.ControlFlowEdgeType.TRUE,
                        TraceabilityMapPayloads.ControlFlowEdgeType.FALSE,
                        TraceabilityMapPayloads.ControlFlowEdgeType.RETURN);
    }

    @Test
    void buildsPreciseGoCfgFromGoParserWorker() throws Exception {
        TraceabilityMapService service = new TraceabilityMapService(null, null, null, new CodeSymbolNormalizer(), null, null, null);
        Class<?> codeIndexType = Arrays.stream(TraceabilityMapService.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("CodeIndex"))
                .findFirst()
                .orElseThrow();
        Constructor<?> constructor = codeIndexType.getDeclaredConstructor(TraceabilityMapService.class);
        constructor.setAccessible(true);
        Object codeIndex = constructor.newInstance(service);
        VerificationModels.AssetSnapshot asset = new VerificationModels.AssetSnapshot(
                "asset-go", "project", AssetType.SOURCE, VerificationModels.SourceType.FILE,
                null, null, null, "service.go", "hash", """
                package demo
                func web3(num3 int) string {
                    switch {
                    case num3 > 0:
                        return "yes"
                    default:
                        return "no"
                    }
                }
                """, "INLINE", null, 0, null, Map.of(), Freshness.SNAPSHOT, "tester", LocalDateTime.now());
        Class<?> sourceUnitType = Arrays.stream(TraceabilityMapService.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("SourceUnit"))
                .findFirst()
                .orElseThrow();
        Constructor<?> sourceUnitConstructor = sourceUnitType.getDeclaredConstructor(String.class, String.class);
        sourceUnitConstructor.setAccessible(true);
        Object sourceUnit = sourceUnitConstructor.newInstance("service.go", asset.content());
        Method fallback = TraceabilityMapService.class.getDeclaredMethod(
                "addSourceAssetWithRegexFallback", codeIndexType, VerificationModels.AssetSnapshot.class,
                boolean.class, sourceUnitType, String.class);
        fallback.setAccessible(true);
        fallback.invoke(service, codeIndex, asset, true, sourceUnit, "code:go:service.go");

        Field graphsField = codeIndexType.getDeclaredField("controlFlowGraphs");
        graphsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<TraceabilityMapPayloads.ControlFlowGraph> graphs =
                (List<TraceabilityMapPayloads.ControlFlowGraph>) graphsField.get(codeIndex);

        assertThat(graphs).hasSize(1);
        TraceabilityMapPayloads.ControlFlowGraph graph = graphs.get(0);
        assertThat(graph.language()).isEqualTo("go");
        assertThat(graph.parseStatus()).isEqualTo(TraceabilityMapPayloads.ControlFlowParseStatus.PRECISE);
        assertThat(graph.nodes()).extracting(TraceabilityMapPayloads.ControlFlowNode::type)
                .contains(TraceabilityMapPayloads.ControlFlowNodeType.SWITCH,
                        TraceabilityMapPayloads.ControlFlowNodeType.CASE,
                        TraceabilityMapPayloads.ControlFlowNodeType.RETURN);
        assertThat(graph.edges()).extracting(TraceabilityMapPayloads.ControlFlowEdge::type)
                .contains(TraceabilityMapPayloads.ControlFlowEdgeType.CASE,
                        TraceabilityMapPayloads.ControlFlowEdgeType.DEFAULT,
                        TraceabilityMapPayloads.ControlFlowEdgeType.RETURN);
    }

    @Test
    void buildsPrecisePythonCfgFromAstWorker() throws Exception {
        TraceabilityMapService service = new TraceabilityMapService(null, null, null, new CodeSymbolNormalizer(), null, null, null);
        Class<?> codeIndexType = Arrays.stream(TraceabilityMapService.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("CodeIndex"))
                .findFirst()
                .orElseThrow();
        Constructor<?> constructor = codeIndexType.getDeclaredConstructor(TraceabilityMapService.class);
        constructor.setAccessible(true);
        Object codeIndex = constructor.newInstance(service);
        VerificationModels.AssetSnapshot asset = new VerificationModels.AssetSnapshot(
                "asset-py", "project", AssetType.SOURCE, VerificationModels.SourceType.FILE,
                null, null, null, "service.py", "hash", """
                def web3(num3):
                    if num3 > 0:
                        value = "yes"
                    return value
                """, "INLINE", null, 0, null, Map.of(), Freshness.SNAPSHOT, "tester", LocalDateTime.now());
        Class<?> sourceUnitType = Arrays.stream(TraceabilityMapService.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("SourceUnit"))
                .findFirst()
                .orElseThrow();
        Constructor<?> sourceUnitConstructor = sourceUnitType.getDeclaredConstructor(String.class, String.class);
        sourceUnitConstructor.setAccessible(true);
        Object sourceUnit = sourceUnitConstructor.newInstance("service.py", asset.content());
        Method fallback = TraceabilityMapService.class.getDeclaredMethod(
                "addSourceAssetWithRegexFallback", codeIndexType, VerificationModels.AssetSnapshot.class,
                boolean.class, sourceUnitType, String.class);
        fallback.setAccessible(true);
        fallback.invoke(service, codeIndex, asset, true, sourceUnit, "code:python:service.py");

        Field graphsField = codeIndexType.getDeclaredField("controlFlowGraphs");
        graphsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<TraceabilityMapPayloads.ControlFlowGraph> graphs =
                (List<TraceabilityMapPayloads.ControlFlowGraph>) graphsField.get(codeIndex);

        assertThat(graphs).hasSize(1);
        TraceabilityMapPayloads.ControlFlowGraph graph = graphs.get(0);
        assertThat(graph.language()).isEqualTo("python");
        assertThat(graph.parseStatus()).isEqualTo(TraceabilityMapPayloads.ControlFlowParseStatus.PRECISE);
        assertThat(graph.nodes()).extracting(TraceabilityMapPayloads.ControlFlowNode::type)
                .contains(TraceabilityMapPayloads.ControlFlowNodeType.DECISION,
                        TraceabilityMapPayloads.ControlFlowNodeType.RETURN);
        assertThat(graph.edges()).extracting(TraceabilityMapPayloads.ControlFlowEdge::type)
                .contains(TraceabilityMapPayloads.ControlFlowEdgeType.TRUE,
                        TraceabilityMapPayloads.ControlFlowEdgeType.FALSE,
                        TraceabilityMapPayloads.ControlFlowEdgeType.RETURN);
    }

    @Test
    void sumsPerFileJacocoXmlCountersWithoutTreatingThemAsOneGlobalHtmlTotal() throws Exception {
        List<UniversalCoverageFile> files = new JacocoCoverageParser().parse("""
                <report><package name="demo">
                  <class name="demo/First" sourcefilename="First.java">
                    <counter type="METHOD" missed="2" covered="1"/>
                    <counter type="CLASS" missed="0" covered="1"/>
                    <counter type="COMPLEXITY" missed="3" covered="2"/>
                  </class>
                  <class name="demo/Second" sourcefilename="Second.java">
                    <counter type="METHOD" missed="3" covered="2"/>
                    <counter type="CLASS" missed="1" covered="0"/>
                    <counter type="COMPLEXITY" missed="4" covered="3"/>
                  </class>
                  <sourcefile name="First.java"><line nr="1" ci="1"/></sourcefile>
                  <sourcefile name="Second.java"><line nr="1" ci="0"/></sourcefile>
                </package></report>
                """.getBytes(StandardCharsets.UTF_8));
        TraceabilityMapService service = new TraceabilityMapService(null, null, null, new CodeSymbolNormalizer(), null, null, null);
        Method summarize = TraceabilityMapService.class.getDeclaredMethod("summarizeCoverageReport", List.class);
        summarize.setAccessible(true);

        Object overview = summarize.invoke(service, files);

        assertThat(value(overview, "totalClasses")).isEqualTo(2);
        assertThat(value(overview, "coveredClasses")).isEqualTo(1);
        assertThat(value(overview, "totalMethods")).isEqualTo(8);
        assertThat(value(overview, "coveredMethods")).isEqualTo(3);
        assertThat(value(overview, "totalComplexity")).isEqualTo(12);
    }

    @Test
    void fallsBackToCurrentProjectBaselineWhenRequestedBaselineIsMissing() {
        VerificationRepository repository = mock(VerificationRepository.class);
        AppService appService = mock(AppService.class);
        Baseline fallback = baseline("baseline-current");
        when(repository.findBaseline("project-1", "baseline-missing")).thenReturn(Optional.empty());
        when(repository.findBaselines("project-1")).thenReturn(List.of(fallback));
        when(repository.findCriteria("baseline-current")).thenReturn(List.of());
        when(repository.findTestcases("baseline-current")).thenReturn(List.of());
        when(repository.findTraceLinks("baseline-current")).thenReturn(List.of());
        when(repository.findAssets("project-1", AssetType.SOURCE)).thenReturn(List.of());

        TraceabilityMapService service = new TraceabilityMapService(
                repository, null, null, new CodeSymbolNormalizer(), appService, null, null);

        TraceabilityMapPayloads.TraceabilityMapResponse response = service.build(
                "project-1", "baseline-missing", null, "BOTH", 4,
                false, false, false, "trace");

        assertThat(response.baseline().id()).isEqualTo("baseline-current");
        assertThat(response.warnings())
                .anyMatch(item -> item.contains("请求的分析基线已不可用")
                        && item.contains("baseline-missing")
                        && item.contains("baseline-current"));
    }

    @Test
    void parsesComplexJavaMethodsWithoutDroppingOverloadsPrivateStaticOrGenerics() throws Exception {
        String source = """
                package web3Server.controller;

                import java.io.Serializable;
                import java.math.BigDecimal;
                import java.util.Map;

                public class Web3Controller {
                    public Map<String, Object> web3(Integer num1, Integer num2) { return Map.of(); }
                    public Map<String, Object> web3(Integer num1, String num2) { return Map.of(); }
                    private void checkUser(User user) { checkUser1(user); }
                    private boolean checkUser1(User user) { return true; }
                    private static String processField(Object field) { return String.valueOf(field); }
                    public static BigDecimal nullToZero(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
                    private <T> T getPage(T t) { return null; }
                    private <T> T getPage1() { return null; }
                    public <U extends Number> void inspect(U u) { System.out.println(u); }
                    public <U extends Number & Serializable> void inspect1(U u) { System.out.println(u); }
                    static class User {}
                }
                """;
        TraceabilityMapService service = new TraceabilityMapService(null, null, null, new CodeSymbolNormalizer(), null, null, null);

        Method parser = TraceabilityMapService.class.getDeclaredMethod("parseJavaSource", String.class, String.class);
        parser.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Object> classes = (List<Object>) parser.invoke(service,
                "web3/src/main/java/web3Server/controller/Web3Controller.java", source);

        Object controller = classes.stream()
                .filter(item -> value(item, "className").equals("web3Server.controller.Web3Controller"))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        List<Object> methods = (List<Object>) value(controller, "methods");
        List<String> signatures = methods.stream().map(item -> (String) value(item, "signature")).toList();

        assertThat(signatures)
                .contains("web3(Integer, Integer)")
                .contains("web3(Integer, String)")
                .contains("checkUser(User)")
                .contains("checkUser1(User)")
                .contains("processField(Object)")
                .contains("nullToZero(BigDecimal)")
                .contains("getPage(T)")
                .contains("getPage1()")
                .contains("inspect(U)")
                .contains("inspect1(U)");
    }

    @Test
    void removesFileMarkerLineBreakWithoutChangingTheFirstSourceLine() throws Exception {
        TraceabilityMapService service = new TraceabilityMapService(null, null, null, new CodeSymbolNormalizer(), null, null, null);
        Method splitter = TraceabilityMapService.class.getDeclaredMethod("splitSourceUnits", String.class, String.class);
        splitter.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Object> units = (List<Object>) splitter.invoke(service, "Imported.java",
                "// FILE: src/main/java/demo/Example.java\r\n"
                        + "package demo;\r\n"
                        + "\r\n"
                        + "class Example {}\r\n");

        assertThat(units).hasSize(1);
        assertThat((String) value(units.get(0), "content"))
                .startsWith("package demo;\r\n")
                .doesNotStartWith("\r\n");
    }

    @Test
    void resolvesCoverageFilePathToFileOrClassInsteadOfLongestMethodLocator() throws Exception {
        TraceabilityMapService service = new TraceabilityMapService(null, null, null, new CodeSymbolNormalizer(), null, null, null);
        Class<?> codeIndexType = Arrays.stream(TraceabilityMapService.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("CodeIndex"))
                .findFirst()
                .orElseThrow();
        Constructor<?> constructor = codeIndexType.getDeclaredConstructor(TraceabilityMapService.class);
        constructor.setAccessible(true);
        Object codeIndex = constructor.newInstance(service);
        Method putCodeNode = codeIndexType.getDeclaredMethod("putCodeNode", TraceabilityMapPayloads.TraceabilityNode.class);
        putCodeNode.setAccessible(true);

        String path = "web3/src/main/java/web3Server/controller/Web302Controller.java";
        String fileId = "code:java:" + path;
        String classId = fileId + "#web3Server.controller.Web302Controller";
        String methodId = classId + ".loginException(String,String,int)";
        putCodeNode.invoke(codeIndex, new TraceabilityMapPayloads.TraceabilityNode(
                fileId, CODE_FILE, "Web302Controller", path, path, "CODE", "java", path,
                null, STATIC, null, Map.of()));
        putCodeNode.invoke(codeIndex, new TraceabilityMapPayloads.TraceabilityNode(
                classId, CODE_CLASS, "Web302Controller", "web3Server.controller.Web302Controller", path,
                "CODE", "java", "web3Server.controller.Web302Controller", fileId, STATIC, null, Map.of()));
        putCodeNode.invoke(codeIndex, new TraceabilityMapPayloads.TraceabilityNode(
                methodId, CODE_METHOD, "loginException", "loginException(String,String,int)", path + ":34",
                "CODE", "java", "web3Server.controller.Web302Controller#loginException", classId,
                STATIC, null, Map.of("line", 34)));

        ClassCoverageIndex coverage = new ClassCoverageIndex();
        coverage.setSourcePath("web3Server/controller/Web302Controller.java");
        coverage.setClassName("web3Server/controller/Web302Controller.java");
        Method resolveCoverageNode = codeIndexType.getDeclaredMethod("resolveCoverageNode", ClassCoverageIndex.class);
        resolveCoverageNode.setAccessible(true);

        String resolvedNodeId = (String) resolveCoverageNode.invoke(codeIndex, coverage);
        assertThat(resolvedNodeId)
                .isIn(fileId, classId)
                .isNotEqualTo(methodId);

        Method applyCoverageSummaryWithParents = codeIndexType.getDeclaredMethod(
                "applyCoverageSummaryWithParents", String.class,
                TraceabilityMapPayloads.CoverageSummary.class, Map.class);
        applyCoverageSummaryWithParents.setAccessible(true);
        applyCoverageSummaryWithParents.invoke(codeIndex, resolvedNodeId,
                new TraceabilityMapPayloads.CoverageSummary(7, 31, 7.0 / 31, 3, 20, 0.15), Map.of());

        ClassCoverageIndex.MethodCoverageDetail loginException = new ClassCoverageIndex.MethodCoverageDetail();
        loginException.setClassName("web3Server/controller/Web302Controller");
        loginException.setMethodName("loginException");
        loginException.setMethodDesc("(Ljava/lang/String;Ljava/lang/String;I)Lweb3Server/domain/R;");
        loginException.setStartLine(37);
        loginException.setTotalLineNumbers(List.of(34, 35, 36, 37, 38, 40, 42, 43, 44, 45, 48, 51, 52, 55, 56, 59, 60));
        loginException.setCoveredLineNumbers(List.of());
        loginException.setTotalLines(17);
        loginException.setCoveredLines(0);
        coverage.setMethods(List.of(loginException));
        Method applyMethodCoverage = codeIndexType.getDeclaredMethod("applyMethodCoverage", ClassCoverageIndex.class);
        applyMethodCoverage.setAccessible(true);
        applyMethodCoverage.invoke(codeIndex, coverage);

        Field nodesField = codeIndexType.getDeclaredField("nodes");
        nodesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, TraceabilityMapPayloads.TraceabilityNode> nodes =
                (Map<String, TraceabilityMapPayloads.TraceabilityNode>) nodesField.get(codeIndex);
        assertThat(nodes.get(methodId).coverage().coveredLines()).isZero();
        assertThat(nodes.get(methodId).coverage().totalLines()).isEqualTo(17);
    }

    @Test
    void marksOnlyMethodsWithMatchedCoverageAsDynamic() throws Exception {
        TraceabilityMapService service = new TraceabilityMapService(null, null, null, new CodeSymbolNormalizer(), null, null, null);
        Class<?> codeIndexType = Arrays.stream(TraceabilityMapService.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("CodeIndex"))
                .findFirst()
                .orElseThrow();
        Constructor<?> constructor = codeIndexType.getDeclaredConstructor(TraceabilityMapService.class);
        constructor.setAccessible(true);
        Object codeIndex = constructor.newInstance(service);
        Method putCodeNode = codeIndexType.getDeclaredMethod("putCodeNode", TraceabilityMapPayloads.TraceabilityNode.class);
        putCodeNode.setAccessible(true);

        String path = "src/main/java/demo/Example.java";
        String fileId = "code:java:" + path;
        String classId = fileId + "#demo.Example";
        String coveredMethodId = classId + ".covered()";
        String missedMethodId = classId + ".missed()";
        putCodeNode.invoke(codeIndex, new TraceabilityMapPayloads.TraceabilityNode(fileId, CODE_FILE, "Example.java", path, path,
                "CODE", "java", path, null, STATIC, null, Map.of()));
        putCodeNode.invoke(codeIndex, new TraceabilityMapPayloads.TraceabilityNode(classId, CODE_CLASS, "Example", "demo.Example", path,
                "CODE", "java", "demo.Example", fileId, STATIC, null, Map.of()));
        putCodeNode.invoke(codeIndex, new TraceabilityMapPayloads.TraceabilityNode(coveredMethodId, CODE_METHOD, "covered", "covered()", path + ":10",
                "CODE", "java", "demo.Example#covered", classId, STATIC, null, Map.of("line", 10)));
        putCodeNode.invoke(codeIndex, new TraceabilityMapPayloads.TraceabilityNode(missedMethodId, CODE_METHOD, "missed", "missed()", path + ":20",
                "CODE", "java", "demo.Example#missed", classId, STATIC, null, Map.of("line", 20)));

        ClassCoverageIndex index = new ClassCoverageIndex();
        index.setSourcePath(path);
        ClassCoverageIndex.MethodCoverageDetail covered = coverageMethod("demo/Example", "covered", 10, 1, 1);
        covered.setComplexity(2);
        covered.setCoveredComplexity(2);
        ClassCoverageIndex.MethodCoverageDetail missed = coverageMethod("demo/Example", "missed", 20, 0, 1);
        missed.setComplexity(1);
        missed.setCoveredComplexity(0);
        index.setMethods(List.of(covered, missed));
        Method applyMethodCoverage = codeIndexType.getDeclaredMethod("applyMethodCoverage", ClassCoverageIndex.class);
        applyMethodCoverage.setAccessible(true);

        @SuppressWarnings("unchecked")
        java.util.Set<String> coveredIds = (java.util.Set<String>) applyMethodCoverage.invoke(codeIndex, index);
        assertThat(coveredIds).containsExactly(coveredMethodId);

        Field nodesField = codeIndexType.getDeclaredField("nodes");
        nodesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, TraceabilityMapPayloads.TraceabilityNode> nodes =
                (Map<String, TraceabilityMapPayloads.TraceabilityNode>) nodesField.get(codeIndex);
        assertThat(nodes.get(coveredMethodId).metadata()).containsEntry("coverageCoveredComplexity", 2);
        assertThat(nodes.get(missedMethodId).metadata()).containsEntry("coverageCoveredComplexity", 0);
    }

    @Test
    void derivesPerMethodCoverageFromFileLinesWhenReportHasNoMatchedMethodDetail() throws Exception {
        TraceabilityMapService service = new TraceabilityMapService(null, null, null, new CodeSymbolNormalizer(), null, null, null);
        Class<?> codeIndexType = Arrays.stream(TraceabilityMapService.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("CodeIndex"))
                .findFirst()
                .orElseThrow();
        Constructor<?> constructor = codeIndexType.getDeclaredConstructor(TraceabilityMapService.class);
        constructor.setAccessible(true);
        Object codeIndex = constructor.newInstance(service);
        Method putCodeNode = codeIndexType.getDeclaredMethod("putCodeNode", TraceabilityMapPayloads.TraceabilityNode.class);
        putCodeNode.setAccessible(true);

        String path = "web3/src/main/java/web3Server/controller/Web302Controller.java";
        String fileId = "code:java:" + path;
        String classId = fileId + "#web3Server.controller.Web302Controller";
        String loginId = classId + ".login(LoginBody)";
        String processFieldId = classId + ".processField(String)";
        putCodeNode.invoke(codeIndex, new TraceabilityMapPayloads.TraceabilityNode(
                fileId, CODE_FILE, "Web302Controller", path, path, "CODE", "java", path,
                null, STATIC, null, Map.of()));
        putCodeNode.invoke(codeIndex, new TraceabilityMapPayloads.TraceabilityNode(
                classId, CODE_CLASS, "Web302Controller", "web3Server.controller.Web302Controller", path,
                "CODE", "java", "web3Server.controller.Web302Controller", fileId, STATIC, null, Map.of()));
        putCodeNode.invoke(codeIndex, new TraceabilityMapPayloads.TraceabilityNode(
                loginId, CODE_METHOD, "login", "login(LoginBody)", path + ":24",
                "CODE", "java", "web3Server.controller.Web302Controller#login", classId, STATIC, null, Map.of("line", 24)));
        putCodeNode.invoke(codeIndex, new TraceabilityMapPayloads.TraceabilityNode(
                processFieldId, CODE_METHOD, "processField", "processField(String)", path + ":30",
                "CODE", "java", "web3Server.controller.Web302Controller#processField", classId, STATIC, null, Map.of("line", 30)));

        ClassCoverageIndex index = new ClassCoverageIndex();
        index.setSourcePath(path);
        index.setTotalLineNumbers(List.of(24, 25, 30, 31));
        index.setCoveredLineNumbers(List.of(24, 25));

        Method applyDerivedMethodCoverage = codeIndexType.getDeclaredMethod(
                "applyDerivedMethodCoverage", String.class, ClassCoverageIndex.class, java.util.Set.class);
        applyDerivedMethodCoverage.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Set<String> coveredIds = (java.util.Set<String>) applyDerivedMethodCoverage.invoke(
                codeIndex, fileId, index, java.util.Set.of());

        Field nodesField = codeIndexType.getDeclaredField("nodes");
        nodesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, TraceabilityMapPayloads.TraceabilityNode> nodes =
                (Map<String, TraceabilityMapPayloads.TraceabilityNode>) nodesField.get(codeIndex);

        assertThat(nodes.get(loginId).coverage()).isNotNull();
        assertThat(nodes.get(loginId).coverage().coveredLines()).isEqualTo(2);
        assertThat(nodes.get(loginId).coverage().totalLines()).isEqualTo(2);
        assertThat(nodes.get(processFieldId).coverage()).isNotNull();
        assertThat(nodes.get(processFieldId).coverage().coveredLines()).isZero();
        assertThat(nodes.get(processFieldId).coverage().totalLines()).isEqualTo(2);
        assertThat(coveredIds).containsExactly(loginId);
    }

    private static ClassCoverageIndex.MethodCoverageDetail coverageMethod(String className, String name, int line, int coveredLines, int totalLines) {
        ClassCoverageIndex.MethodCoverageDetail method = new ClassCoverageIndex.MethodCoverageDetail();
        method.setClassName(className);
        method.setMethodName(name);
        method.setMethodDesc("()");
        method.setStartLine(line);
        method.setCoveredLines(coveredLines);
        method.setTotalLines(totalLines);
        method.setCoveredLineNumbers(coveredLines > 0 ? List.of(line) : List.of());
        method.setTotalLineNumbers(List.of(line));
        method.setCovered(coveredLines > 0);
        return method;
    }

    private static Object value(Object record, String accessor) {
        try {
            Method method = record.getClass().getDeclaredMethod(accessor);
            method.setAccessible(true);
            return method.invoke(record);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private static Baseline baseline(String id) {
        LocalDateTime now = LocalDateTime.now();
        return new Baseline(
                id,
                "project-1",
                "Current baseline",
                null,
                null,
                null,
                null,
                null,
                "app-1",
                null,
                null,
                null,
                VerificationModels.ANALYZER_VERSION,
                BaselineStatus.COMPLETED,
                Freshness.LIVE,
                "tester",
                now,
                now);
    }
}
