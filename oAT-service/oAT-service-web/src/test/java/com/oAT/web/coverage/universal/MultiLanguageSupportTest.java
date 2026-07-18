package com.oAT.web.coverage.universal;

import com.oAT.web.verification.impact.ImpactModels.SymbolKind;
import com.oAT.web.verification.impact.ImpactModels.SymbolSnapshot;
import com.oAT.web.verification.impact.PolyglotLanguageAnalyzer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiLanguageSupportTest {
    private final PolyglotLanguageAnalyzer analyzer = new PolyglotLanguageAnalyzer();

    @Test
    void extractsFunctionsForEveryConfiguredNonJavaLanguage() {
        assertMethod("src/api.ts", "export function createOrder(id: string) { return save(id); }");
        assertMethod("cmd/main.go", "func CreateOrder(id string) { save(id) }");
        assertMethod("app/order.py", "def create_order(id):\n    return save(id)\n");
        assertMethod("src/order.cpp", "void createOrder(int id) { save(id); }");
    }

    @Test
    void parsesLcovGoCoverAndPythonJson() {
        byte[] lcov = "SF:src/order.cpp\nDA:3,2\nFN:3,createOrder\nFNDA:2,createOrder\nend_of_record\n".getBytes(StandardCharsets.UTF_8);
        assertEquals(1, new CppCoverageParser().parse(lcov).size());
        assertEquals(2, new CppCoverageParser().parse(lcov).get(0).getLines().get(0).getCoveredCount());
        byte[] go = "mode: set\nexample/order.go:3.1,5.2 2 1\n".getBytes(StandardCharsets.UTF_8);
        assertEquals(3, new GoCoverageParser().parse(go).get(0).getLines().size());
        byte[] python = "{\"files\":{\"app/order.py\":{\"executed_lines\":[2],\"missing_lines\":[3]}}}".getBytes(StandardCharsets.UTF_8);
        assertEquals(2, new PythonCoverageParser().parse(python).get(0).getLines().size());
    }

    private void assertMethod(String path, String source) {
        List<SymbolSnapshot> symbols = analyzer.analyze(path, source);
        assertTrue(symbols.stream().anyMatch(symbol -> symbol.kind() == SymbolKind.METHOD));
    }
}
