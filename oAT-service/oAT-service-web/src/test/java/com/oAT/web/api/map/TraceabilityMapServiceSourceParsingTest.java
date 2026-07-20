package com.oAT.web.api.map;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
        TraceabilityMapService service = new TraceabilityMapService(null, null, null, new CodeSymbolNormalizer(), null, null);

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
