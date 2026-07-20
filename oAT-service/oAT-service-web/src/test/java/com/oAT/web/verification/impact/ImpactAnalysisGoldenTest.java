package com.oAT.web.verification.impact;

import com.oAT.web.verification.impact.ImpactModels.ChangeFacet;
import com.oAT.web.verification.impact.ImpactModels.EdgeType;
import com.oAT.web.verification.impact.ImpactModels.GraphEdge;
import com.oAT.web.verification.impact.ImpactModels.SymbolChange;
import com.oAT.web.verification.impact.ImpactModels.SymbolChangeType;
import com.oAT.web.verification.impact.ImpactModels.SymbolKind;
import com.oAT.web.verification.impact.ImpactModels.SymbolSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImpactAnalysisGoldenTest {
    private final JavaTreeSitterAnalyzer analyzer = new JavaTreeSitterAnalyzer();
    private final StructuralDiffEngine diff = new StructuralDiffEngine();
    private final ImpactPropagationEngine propagation = new ImpactPropagationEngine();

    @Test
    void ignoresCommentOnlyChanges() {
        String oldSource = "package demo; class OrderService { String create(String id) { return id; } }";
        String newSource = "package demo; // documentation\nclass OrderService { String create(String id) { return id; } }";
        assertTrue(diff.diff(analyzer.analyze("OrderService.java", oldSource), analyzer.analyze("OrderService.java", newSource)).isEmpty());
    }

    @Test
    void propagatesToDirectCallerWithExplainablePath() {
        SymbolSnapshot changed = analyzer.analyze("OrderService.java", "package demo; class OrderService { String create(String id) { return id; } }").stream()
                .filter(symbol -> symbol.kind() == SymbolKind.METHOD).findFirst().orElseThrow();
        SymbolSnapshot caller = analyzer.analyze("OrderController.java", "package demo; class OrderController { String submit(String id) { return new OrderService().create(id); } }").stream()
                .filter(symbol -> symbol.kind() == SymbolKind.METHOD).findFirst().orElseThrow();
        SymbolChange change = new SymbolChange(changed.key(), changed.key(), SymbolChangeType.MODIFY, List.of(ChangeFacet.BODY), changed, changed, List.of(changed.range()));
        var impacts = propagation.propagate(List.of(change), List.of(new GraphEdge(caller.key(), changed.key(), EdgeType.CALLS, .9d, false, "golden-call")), 3);
        assertEquals(2, impacts.size());
        assertTrue(impacts.stream().anyMatch(impact -> impact.targetSymbol().equals(caller.key()) && impact.path().symbols().size() == 2));
    }

    @Test
    void doesNotTreatControlStatementsAsMethods() {
        String source = """
                package demo;
                class Web3Controller {
                    void web3Branch(User user) {
                        if (user != null && user.name != null) {
                            submit(user);
                        }
                    }
                    void submit(User user) { }
                }
                class User { String name; }
                """;
        List<String> signatures = analyzer.analyze("Web3Controller.java", source).stream()
                .filter(symbol -> symbol.kind() == SymbolKind.METHOD)
                .map(SymbolSnapshot::signature)
                .toList();

        assertTrue(signatures.contains("web3Branch(User user)"));
        assertTrue(signatures.contains("submit(User user)"));
        assertTrue(signatures.stream().noneMatch(signature -> signature.startsWith("if(")));
    }
}
