package com.oAT.web.api.map;

import com.oAT.web.esDao.entity.ClassCoverageIndex;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static com.oAT.web.api.map.TraceabilityMapPayloads.EvidenceState.STATIC;
import static com.oAT.web.api.map.TraceabilityMapPayloads.NodeKind.CODE_CLASS;
import static com.oAT.web.api.map.TraceabilityMapPayloads.NodeKind.CODE_FILE;
import static com.oAT.web.api.map.TraceabilityMapPayloads.NodeKind.CODE_METHOD;

class TraceabilityMapServiceSourceParsingTest {

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
        loginException.setStartLine(34);
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

    private static Object value(Object record, String accessor) {
        try {
            Method method = record.getClass().getDeclaredMethod(accessor);
            method.setAccessible(true);
            return method.invoke(record);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }
}
