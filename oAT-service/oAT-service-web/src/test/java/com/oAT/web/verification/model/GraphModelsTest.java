package com.oAT.web.verification.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class GraphModelsTest {
    @Test
    void buildsStableAndRevisionIndependentLogicalSymbolIds() {
        GraphModels.SymbolIdentity first = new GraphModels.SymbolIdentity(
                "https://git.example.com/team/order.git", "abc123", "src\\main/java/OrderService.java",
                "com.example.OrderService", "create", "OrderRequest", "Order");
        GraphModels.SymbolIdentity second = new GraphModels.SymbolIdentity(
                "https://git.example.com/team/order.git", "def456", "src/main/java/OrderService.java",
                "com.example.OrderService", "create", "OrderRequest", "Order");

        assertEquals("https://git.example.com/team/order.git@abc123:src/main/java/OrderService.java:com.example.OrderService#create(OrderRequest):Order",
                first.stableId());
        assertNotEquals(first.stableId(), second.stableId());
        assertEquals(first.logicalId(), second.logicalId());
    }

    @Test
    void generatesDeterministicEdgeIds() {
        String first = GraphModels.edgeId("snapshot-1", "source", "target",
                GraphModels.GraphEdgeType.CALLS_STATIC, GraphModels.EvidenceKind.STATIC_RESOLVED, null);
        String second = GraphModels.edgeId("snapshot-1", "source", "target",
                GraphModels.GraphEdgeType.CALLS_STATIC, GraphModels.EvidenceKind.STATIC_RESOLVED, null);

        assertEquals(first, second);
        assertEquals(64, first.length());
    }
}
