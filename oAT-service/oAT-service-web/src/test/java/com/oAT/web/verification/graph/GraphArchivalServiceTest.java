package com.oAT.web.verification.graph;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GraphArchivalServiceTest {

    private JdbcTemplate jdbc;
    private GraphArchivalService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        service = new GraphArchivalService(jdbc);
        // Default: nothing to archive
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
    }

    // ── archiveStaleGraphFacts ────────────────────────────────────────────────

    @Test
    void archives_nodes_and_edges_older_than_threshold() {
        when(jdbc.update(contains("oat_graph_node"), any(Object[].class))).thenReturn(5);
        when(jdbc.update(contains("oat_graph_edge"),  any(Object[].class))).thenReturn(12);

        int[] result = service.archiveStaleGraphFacts(30);

        assertEquals(5,  result[0], "node count");
        assertEquals(12, result[1], "edge count");
        verify(jdbc).update(contains("oat_graph_node"), any(Object[].class));
        verify(jdbc).update(contains("oat_graph_edge"),  any(Object[].class));
    }

    @Test
    void returns_zero_counts_when_nothing_to_archive() {
        int[] result = service.archiveStaleGraphFacts(90);

        assertEquals(0, result[0]);
        assertEquals(0, result[1]);
    }

    @Test
    void default_threshold_uses_WARM_TIER_DAYS() {
        service.archiveStaleGraphFacts();

        // Exactly two UPDATE statements: one for nodes, one for edges
        verify(jdbc, times(2)).update(anyString(), any(Object[].class));
    }

    // ── archiveOldAnalysisJobs ────────────────────────────────────────────────

    @Test
    void archives_terminal_analysis_jobs_older_than_threshold() {
        when(jdbc.update(contains("oat_verification_analysis_job"), any(Object[].class))).thenReturn(7);

        int count = service.archiveOldAnalysisJobs(90);

        assertEquals(7, count);
        verify(jdbc).update(contains("SUCCEEDED"), any(Object[].class));
    }

    @Test
    void returns_zero_when_no_jobs_to_archive() {
        assertEquals(0, service.archiveOldAnalysisJobs(90));
    }

    // ── runAll ────────────────────────────────────────────────────────────────

    @Test
    void runAll_returns_combined_summary() {
        when(jdbc.update(contains("oat_graph_node"),              any(Object[].class))).thenReturn(3);
        when(jdbc.update(contains("oat_graph_edge"),              any(Object[].class))).thenReturn(8);
        when(jdbc.update(contains("oat_verification_analysis_job"), any(Object[].class))).thenReturn(2);

        var summary = service.runAll();

        assertEquals(3, summary.archivedNodes());
        assertEquals(8, summary.archivedEdges());
        assertEquals(2, summary.archivedJobs());
        assertTrue(summary.toString().contains("nodes=3"));
    }
}
