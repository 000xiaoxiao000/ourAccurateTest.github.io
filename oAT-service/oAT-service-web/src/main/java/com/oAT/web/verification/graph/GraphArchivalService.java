package com.oAT.web.verification.graph;

import com.oAT.web.logging.LogFields;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * Cold-tier archival for graph nodes and edges (plan §12).
 *
 * Three-tier lifecycle for graph facts:
 *
 *   HOT  (invalidated_at IS NULL, archived_at IS NULL)
 *        — active, participates in all queries
 *
 *   WARM (invalidated_at IS NOT NULL, archived_at IS NULL)
 *        — logically stale but physically present; reachable for audit/diff
 *        — default state after DiffInvalidationService or IncrementalRecomputeService
 *
 *   COLD (archived_at IS NOT NULL)
 *        — soft-archived after WARM_TIER_DAYS without activity; excluded from
 *          normal active queries, still accessible for historical read
 *
 * Nodes/edges are NOT deleted — deletion would break audit trails and baseline
 * comparison. Hard deletion is a separate data-governance operation outside
 * this service's scope.
 */
@Service
public class GraphArchivalService {
    private static final Logger log = LoggerFactory.getLogger(GraphArchivalService.class);

    /** Days after invalidation before a node/edge is moved to the cold tier. */
    public static final int WARM_TIER_DAYS = 90;

    private final JdbcTemplate jdbc;

    public GraphArchivalService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Mark graph nodes and edges that were invalidated more than {@code warmTierDays} days ago
     * as cold-archived (sets archived_at). Returns [nodeCount, edgeCount].
     */
    public int[] archiveStaleGraphFacts(int warmTierDays) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(warmTierDays);
        Timestamp ts = Timestamp.valueOf(cutoff);

        int nodes = jdbc.update("""
                UPDATE oat_graph_node
                SET archived_at = CURRENT_TIMESTAMP
                WHERE invalidated_at IS NOT NULL
                  AND invalidated_at < ?
                  AND archived_at IS NULL
                """, ts);

        int edges = jdbc.update("""
                UPDATE oat_graph_edge
                SET archived_at = CURRENT_TIMESTAMP
                WHERE invalidated_at IS NOT NULL
                  AND invalidated_at < ?
                  AND archived_at IS NULL
                """, ts);

        if (nodes > 0 || edges > 0) {
            log.info("event=graph.archive_stale_facts.completed {}", LogFields.of(LogFields.map(
                    "archived_nodes", nodes,
                    "archived_edges", edges,
                    "warm_tier_days", warmTierDays)));
        }
        return new int[]{nodes, edges};
    }

    /** Run with the default warm-tier threshold. Returns [nodeCount, edgeCount]. */
    public int[] archiveStaleGraphFacts() {
        return archiveStaleGraphFacts(WARM_TIER_DAYS);
    }

    /**
     * Archive analysis job rows whose terminal state (SUCCEEDED or FAILED) is older
     * than {@code warmTierDays} days. This prevents the job table from growing unboundedly
     * for high-frequency incremental runs.
     *
     * @return number of job rows archived
     */
    public int archiveOldAnalysisJobs(int warmTierDays) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(warmTierDays);
        int rows = jdbc.update("""
                UPDATE oat_verification_analysis_job
                SET checkpoint_step = 'ARCHIVED'
                WHERE status IN ('SUCCEEDED', 'FAILED')
                  AND update_time < ?
                  AND (checkpoint_step IS NULL OR checkpoint_step <> 'ARCHIVED')
                """, Timestamp.valueOf(cutoff));
        if (rows > 0) {
            log.info("event=analysis_job.archive_old.completed {}", LogFields.of(LogFields.map(
                    "archived_rows", rows,
                    "warm_tier_days", warmTierDays)));
        }
        return rows;
    }

    /** Run both graph and job archival with default thresholds. Returns summary string. */
    public ArchivalSummary runAll() {
        int[] graphCounts = archiveStaleGraphFacts();
        int jobCount = archiveOldAnalysisJobs(WARM_TIER_DAYS);
        return new ArchivalSummary(graphCounts[0], graphCounts[1], jobCount);
    }

    public record ArchivalSummary(int archivedNodes, int archivedEdges, int archivedJobs) {
        @Override
        public String toString() {
            return "GraphArchivalSummary{nodes=" + archivedNodes
                    + ", edges=" + archivedEdges
                    + ", jobs=" + archivedJobs + "}";
        }
    }
}
