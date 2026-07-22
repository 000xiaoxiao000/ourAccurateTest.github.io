package com.oAT.web.verification.graph;

import com.oAT.web.common.UtilJson;
import com.oAT.web.verification.model.GraphModels.EvidenceKind;
import com.oAT.web.verification.model.GraphModels.GraphEdgeType;
import com.oAT.web.verification.model.GraphModels.GraphNodeKind;
import com.oAT.web.verification.model.GraphModels.SnapshotKind;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class GraphRepository {
    private final JdbcTemplate jdbc;

    public GraphRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void saveSnapshot(GraphSnapshot snapshot) {
        jdbc.update("""
                INSERT INTO oat_graph_snapshot (id, project_id, baseline_id, repository_url, source_commit,
                    snapshot_kind, analyzer_version, input_hash, status, attributes_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (baseline_id, snapshot_kind, analyzer_version, input_hash) DO UPDATE
                SET id = EXCLUDED.id, repository_url = EXCLUDED.repository_url, source_commit = EXCLUDED.source_commit,
                    status = EXCLUDED.status, invalidated_at = NULL, attributes_json = EXCLUDED.attributes_json
                """, snapshot.id(), snapshot.projectId(), snapshot.baselineId(), snapshot.repositoryUrl(), snapshot.sourceCommit(),
                snapshot.kind().name(), snapshot.analyzerVersion(), snapshot.inputHash(), snapshot.status(), json(snapshot.attributes()));
    }

    public List<GraphSnapshot> findActiveSnapshots(String baselineId) {
        return jdbc.query("""
                SELECT * FROM oat_graph_snapshot WHERE baseline_id = ? AND invalidated_at IS NULL
                ORDER BY snapshot_kind, created_at DESC
                """, this::snapshot, baselineId);
    }

    public Optional<GraphSnapshot> findActiveSnapshot(String baselineId, SnapshotKind kind) {
        return jdbc.query("""
                SELECT * FROM oat_graph_snapshot WHERE baseline_id = ? AND snapshot_kind = ?
                AND invalidated_at IS NULL ORDER BY created_at DESC LIMIT 1
                """, this::snapshot, baselineId, kind.name()).stream().findFirst();
    }

    public void invalidateSnapshots(String baselineId, SnapshotKind kind) {
        jdbc.update("""
                UPDATE oat_graph_snapshot SET invalidated_at = CURRENT_TIMESTAMP, status = 'STALE'
                WHERE baseline_id = ? AND snapshot_kind = ? AND invalidated_at IS NULL
                """, baselineId, kind.name());
        jdbc.update("""
                UPDATE oat_graph_node SET invalidated_at = CURRENT_TIMESTAMP
                WHERE snapshot_id IN (SELECT id FROM oat_graph_snapshot WHERE baseline_id = ? AND snapshot_kind = ?)
                  AND invalidated_at IS NULL
                """, baselineId, kind.name());
        jdbc.update("""
                UPDATE oat_graph_edge SET invalidated_at = CURRENT_TIMESTAMP
                WHERE snapshot_id IN (SELECT id FROM oat_graph_snapshot WHERE baseline_id = ? AND snapshot_kind = ?)
                  AND invalidated_at IS NULL
                """, baselineId, kind.name());
        jdbc.update("UPDATE oat_graph_aggregate SET invalidated_at = CURRENT_TIMESTAMP WHERE baseline_id = ? AND invalidated_at IS NULL", baselineId);
    }

    public void invalidateAll(String baselineId) {
        jdbc.update("UPDATE oat_graph_snapshot SET invalidated_at = CURRENT_TIMESTAMP, status = 'STALE' WHERE baseline_id = ? AND invalidated_at IS NULL", baselineId);
        jdbc.update("UPDATE oat_graph_node SET invalidated_at = CURRENT_TIMESTAMP WHERE baseline_id = ? AND invalidated_at IS NULL", baselineId);
        jdbc.update("UPDATE oat_graph_edge SET invalidated_at = CURRENT_TIMESTAMP WHERE baseline_id = ? AND invalidated_at IS NULL", baselineId);
        jdbc.update("UPDATE oat_graph_aggregate SET invalidated_at = CURRENT_TIMESTAMP WHERE baseline_id = ? AND invalidated_at IS NULL", baselineId);
    }

    public void saveNode(GraphNode node) {
        jdbc.update("""
                INSERT INTO oat_graph_node (id, snapshot_id, baseline_id, project_id, node_kind, stable_symbol_id,
                    logical_symbol_id, locator, display_name, content_hash, attributes_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (id) DO UPDATE SET locator = EXCLUDED.locator, display_name = EXCLUDED.display_name,
                    content_hash = EXCLUDED.content_hash, attributes_json = EXCLUDED.attributes_json, invalidated_at = NULL
                """, node.id(), node.snapshotId(), node.baselineId(), node.projectId(), node.kind().name(), node.stableSymbolId(),
                node.logicalSymbolId(), node.locator(), node.displayName(), node.contentHash(), json(node.attributes()));
    }

    public void saveEdge(GraphEdge edge) {
        jdbc.update("""
                INSERT INTO oat_graph_edge (id, snapshot_id, baseline_id, project_id, source_node_id, target_node_id,
                    edge_type, evidence_kind, evidence_level, confidence, execution_id, evidence_locator, attributes_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (id) DO UPDATE SET confidence = EXCLUDED.confidence, evidence_locator = EXCLUDED.evidence_locator,
                    attributes_json = EXCLUDED.attributes_json, invalidated_at = NULL
                """, edge.id(), edge.snapshotId(), edge.baselineId(), edge.projectId(), edge.sourceNodeId(), edge.targetNodeId(),
                edge.type().name(), edge.evidenceKind().name(), edge.evidenceLevel(), edge.confidence(), edge.executionId(),
                edge.evidenceLocator(), json(edge.attributes()));
    }

    public void saveAggregate(GraphAggregate aggregate) {
        jdbc.update("""
                INSERT INTO oat_graph_aggregate (id, baseline_id, project_id, aggregate_kind, subject_id, source_hash, payload_json)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (id) DO UPDATE
                SET project_id = EXCLUDED.project_id, aggregate_kind = EXCLUDED.aggregate_kind,
                    subject_id = EXCLUDED.subject_id, source_hash = EXCLUDED.source_hash,
                    payload_json = EXCLUDED.payload_json, calculated_at = CURRENT_TIMESTAMP, invalidated_at = NULL
                """, aggregate.id(), aggregate.baselineId(), aggregate.projectId(), aggregate.kind(), aggregate.subjectId(),
                aggregate.sourceHash(), json(aggregate.payload()));
    }

    public void invalidateAggregates(String baselineId, String kind) {
        jdbc.update("""
                UPDATE oat_graph_aggregate SET invalidated_at = CURRENT_TIMESTAMP
                WHERE baseline_id = ? AND aggregate_kind = ? AND invalidated_at IS NULL
                """, baselineId, kind);
    }

    public int invalidateAggregateSubject(String baselineId, String kind, String subjectId) {
        return jdbc.update("""
                UPDATE oat_graph_aggregate SET invalidated_at = CURRENT_TIMESTAMP
                WHERE baseline_id = ? AND aggregate_kind = ? AND subject_id = ? AND invalidated_at IS NULL
                """, baselineId, kind, subjectId);
    }

    public List<GraphAggregate> findAggregates(String baselineId, String kind) {
        return jdbc.query("""
                SELECT * FROM oat_graph_aggregate WHERE baseline_id = ? AND aggregate_kind = ?
                ORDER BY calculated_at DESC
                """, this::aggregate, baselineId, kind);
    }

    public List<GraphAggregate> findActiveAggregates(String baselineId, String kind) {
        return jdbc.query("""
                SELECT * FROM oat_graph_aggregate WHERE baseline_id = ? AND aggregate_kind = ? AND invalidated_at IS NULL
                ORDER BY subject_id
                """, this::aggregate, baselineId, kind);
    }

    public List<GraphNode> findActiveNodesByKind(String baselineId, GraphNodeKind kind) {
        return jdbc.query("""
                SELECT * FROM oat_graph_node WHERE baseline_id = ? AND node_kind = ? AND invalidated_at IS NULL
                ORDER BY display_name
                """, this::node, baselineId, kind.name());
    }

    public List<GraphEdge> findActiveEdgesByType(String baselineId, GraphEdgeType type) {
        return jdbc.query("""
                SELECT * FROM oat_graph_edge WHERE baseline_id = ? AND edge_type = ? AND invalidated_at IS NULL
                ORDER BY source_node_id
                """, this::edge, baselineId, type.name());
    }

    public List<GraphEdge> findActiveEdgesFrom(String baselineId, String sourceNodeId) {
        return jdbc.query("""
                SELECT * FROM oat_graph_edge WHERE baseline_id = ? AND source_node_id = ? AND invalidated_at IS NULL
                """, this::edge, baselineId, sourceNodeId);
    }

    public int countActiveNodesByKind(String baselineId, GraphNodeKind kind) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM oat_graph_node WHERE baseline_id = ? AND node_kind = ? AND invalidated_at IS NULL
                """, Integer.class, baselineId, kind.name());
        return count == null ? 0 : count;
    }

    public Optional<GraphNode> findActiveBranchNode(String baselineId, String methodStableSymbol, int line, int branchIndex) {
        return jdbc.query("""
                SELECT * FROM oat_graph_node WHERE baseline_id = ? AND node_kind = 'BRANCH' AND invalidated_at IS NULL
                AND attributes_json ->> 'line' = ? AND attributes_json ->> 'branchIndex' = ?
                AND display_name ILIKE ? ORDER BY display_name LIMIT 1
                """, this::node, baselineId, String.valueOf(line), String.valueOf(branchIndex),
                "%L" + line + "[" + branchIndex + "]").stream().findFirst();
    }

    public List<GraphNode> findActiveBranchNodesForMethod(String baselineId, String methodDisplayName) {
        return jdbc.query("""
                SELECT * FROM oat_graph_node WHERE baseline_id = ? AND node_kind = 'BRANCH' AND invalidated_at IS NULL
                AND display_name ILIKE ? ORDER BY display_name
                """, this::node, baselineId, methodDisplayName + "#L%");
    }

    public boolean hasCoveredEdgeToTarget(String baselineId, String targetNodeId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM oat_graph_edge WHERE baseline_id = ? AND target_node_id = ?
                AND edge_type = 'COVERED' AND invalidated_at IS NULL
                """, Integer.class, baselineId, targetNodeId);
        return count != null && count > 0;
    }

    public java.util.Set<String> findDynamicallyEvidencedNodeIds(String baselineId) {
        return new java.util.HashSet<>(jdbc.query("""
                SELECT DISTINCT node_id FROM (
                  SELECT target_node_id AS node_id FROM oat_graph_edge
                    WHERE baseline_id = ? AND edge_type = 'COVERED' AND invalidated_at IS NULL
                  UNION
                  SELECT source_node_id AS node_id FROM oat_graph_edge
                    WHERE baseline_id = ? AND edge_type = 'CALLS_RUNTIME' AND invalidated_at IS NULL
                  UNION
                  SELECT target_node_id AS node_id FROM oat_graph_edge
                    WHERE baseline_id = ? AND edge_type = 'CALLS_RUNTIME' AND invalidated_at IS NULL
                  UNION
                  SELECT target_node_id AS node_id FROM oat_graph_edge
                    WHERE baseline_id = ? AND edge_type = 'TOUCHED' AND invalidated_at IS NULL
                ) evidence
                """, (rs, row) -> rs.getString("node_id"), baselineId, baselineId, baselineId, baselineId));
    }

    public java.util.Set<String> findStaticallyReachableNodeIds(String baselineId) {
        return new java.util.HashSet<>(jdbc.query("""
                SELECT DISTINCT node_id FROM (
                  SELECT source_node_id AS node_id FROM oat_graph_edge
                    WHERE baseline_id = ? AND edge_type = 'CALLS_STATIC' AND invalidated_at IS NULL
                  UNION
                  SELECT target_node_id AS node_id FROM oat_graph_edge
                    WHERE baseline_id = ? AND edge_type = 'CALLS_STATIC' AND invalidated_at IS NULL
                ) reachable
                """, (rs, row) -> rs.getString("node_id"), baselineId, baselineId));
    }

    public List<GraphNode> findMethodSubtree(String baselineId, List<String> rootNodeIds, int depth) {
        if (rootNodeIds == null || rootNodeIds.isEmpty()) return List.of();
        return jdbc.query("""
                WITH RECURSIVE walk(id, level) AS (
                  SELECT id, 0 FROM oat_graph_node WHERE baseline_id = ? AND id = ANY (?) AND invalidated_at IS NULL
                  UNION
                  SELECT CASE WHEN e.source_node_id = w.id THEN e.target_node_id ELSE e.source_node_id END, w.level + 1
                  FROM walk w JOIN oat_graph_edge e ON (e.source_node_id = w.id OR e.target_node_id = w.id)
                  WHERE e.baseline_id = ? AND e.invalidated_at IS NULL AND e.edge_type IN ('CALLS_STATIC','CALLS_RUNTIME','CONTAINS')
                    AND w.level < ?
                ) SELECT DISTINCT n.* FROM oat_graph_node n JOIN walk w ON w.id = n.id
                WHERE n.invalidated_at IS NULL
                """, this::node, baselineId, rootNodeIds.toArray(String[]::new), baselineId, depth);
    }

    public List<GraphNode> findNodesBySymbolPrefix(String baselineId, GraphNodeKind kind, String prefix) {
        return jdbc.query("""
                SELECT * FROM oat_graph_node WHERE baseline_id = ? AND node_kind = ? AND invalidated_at IS NULL
                AND (stable_symbol_id ILIKE ? OR logical_symbol_id ILIKE ?) ORDER BY display_name
                """, this::node, baselineId, kind.name(), "%" + prefix + "%", "%" + prefix + "%");
    }

    public void invalidateNodeSubtree(String baselineId, List<String> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) return;
        String[] ids = nodeIds.toArray(String[]::new);
        jdbc.update("""
                UPDATE oat_graph_edge SET invalidated_at = CURRENT_TIMESTAMP
                WHERE baseline_id = ? AND invalidated_at IS NULL
                AND (source_node_id = ANY (?) OR target_node_id = ANY (?))
                """, baselineId, ids, ids);
        jdbc.update("""
                UPDATE oat_graph_node SET invalidated_at = CURRENT_TIMESTAMP
                WHERE baseline_id = ? AND invalidated_at IS NULL AND id = ANY (?)
                """, baselineId, ids);
    }

    public List<RuntimeExecution> findRuntimeExecutions(String baselineId) {
        return jdbc.query("""
                SELECT * FROM oat_runtime_execution WHERE baseline_id = ? ORDER BY captured_at DESC
                """, this::runtimeExecution, baselineId);
    }

    private RuntimeExecution runtimeExecution(ResultSet rs, int row) throws SQLException {
        return new RuntimeExecution(rs.getString("id"), rs.getString("project_id"), rs.getString("baseline_id"),
                rs.getString("source_commit"), rs.getString("external_execution_id"), rs.getString("environment"),
                rs.getString("collector_version"), rs.getString("trace_hash"), rs.getString("coverage_hash"),
                offset(rs, "started_at"), offset(rs, "finished_at"), offset(rs, "captured_at"), map(rs.getString("attributes_json")));
    }

    private java.time.OffsetDateTime offset(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant().atOffset(java.time.OffsetDateTime.now().getOffset());
    }
    public void saveRuntimeExecution(RuntimeExecution execution) {
        jdbc.update("""
                INSERT INTO oat_runtime_execution (id, project_id, baseline_id, source_commit, external_execution_id,
                    environment, collector_version, trace_hash, coverage_hash, started_at, finished_at, captured_at, attributes_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (id) DO UPDATE SET finished_at = EXCLUDED.finished_at, attributes_json = EXCLUDED.attributes_json
                """, execution.id(), execution.projectId(), execution.baselineId(), execution.sourceCommit(), execution.externalExecutionId(),
                execution.environment(), execution.collectorVersion(), execution.traceHash(), execution.coverageHash(), execution.startedAt(),
                execution.finishedAt(), execution.capturedAt(), json(execution.attributes()));
    }

    public int countActiveNodes(String baselineId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM oat_graph_node WHERE baseline_id = ? AND invalidated_at IS NULL",
                Integer.class, baselineId);
        return count == null ? 0 : count;
    }

    public int countActiveEdgesAmong(String baselineId, List<String> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) return 0;
        String[] ids = nodeIds.toArray(String[]::new);
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM oat_graph_edge WHERE baseline_id = ? AND invalidated_at IS NULL
                AND source_node_id = ANY (?) AND target_node_id = ANY (?)
                """, Integer.class, baselineId, ids, ids);
        return count == null ? 0 : count;
    }

    public boolean hasRuntimeTraceForTestcase(String baselineId, String testcaseKey) {
        if (testcaseKey == null || testcaseKey.isBlank()) return false;
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM oat_graph_edge WHERE baseline_id = ? AND edge_type = 'CALLS_RUNTIME'
                AND evidence_kind = 'DYNAMIC_TRACE' AND invalidated_at IS NULL
                AND attributes_json ->> 'testcaseKey' = ?
                """, Integer.class, baselineId, testcaseKey);
        return count != null && count > 0;
    }

    public boolean hasMethodCoverageForSymbol(String baselineId, String symbol) {
        if (symbol == null || symbol.isBlank()) return false;
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM oat_graph_edge edge
                JOIN oat_graph_node target ON target.id = edge.target_node_id
                JOIN oat_graph_node coverage ON coverage.id = edge.source_node_id
                WHERE edge.baseline_id = ? AND edge.edge_type = 'COVERED'
                AND edge.evidence_kind = 'COVERAGE' AND edge.evidence_level = 'E4'
                AND edge.invalidated_at IS NULL AND target.invalidated_at IS NULL AND coverage.invalidated_at IS NULL
                AND coverage.attributes_json ->> 'scope' = 'METHOD'
                AND (target.stable_symbol_id ILIKE ? OR target.logical_symbol_id ILIKE ? OR target.display_name = ?)
                """, Integer.class, baselineId, "%" + symbol + "%", "%" + symbol + "%", symbol);
        return count != null && count > 0;
    }

    public Optional<GraphNode> findActiveMethod(String baselineId, String className, String methodName,
                                                String descriptor, int startLine) {
        if (className == null || className.isBlank() || methodName == null || methodName.isBlank()) return Optional.empty();
        String stablePattern = "%" + className + "#" + methodName + "%";
        return jdbc.query("""
                SELECT * FROM oat_graph_node WHERE baseline_id = ? AND node_kind = 'METHOD' AND invalidated_at IS NULL
                AND stable_symbol_id ILIKE ?
                AND (? = '' OR attributes_json ->> 'descriptor' = ?)
                ORDER BY CASE WHEN ? > 0 AND locator LIKE ? THEN 0 ELSE 1 END, display_name
                LIMIT 1
                """, this::node, baselineId, stablePattern, descriptor == null ? "" : descriptor,
                descriptor == null ? "" : descriptor, startLine, "%:" + startLine).stream().findFirst();
    }

    public Optional<GraphNode> findActiveNodeBySymbol(String baselineId, String symbol) {
        return jdbc.query("""
                SELECT * FROM oat_graph_node WHERE baseline_id = ? AND invalidated_at IS NULL
                AND (stable_symbol_id ILIKE ? OR logical_symbol_id ILIKE ? OR display_name = ?)
                ORDER BY CASE WHEN display_name = ? THEN 0 ELSE 1 END LIMIT 1
                """, this::node, baselineId, "%" + symbol + "%", "%" + symbol + "%", symbol, symbol).stream().findFirst();
    }

    public Optional<GraphNode> findActiveNodeByLocator(String baselineId, String locator) {
        return jdbc.query("""
                SELECT * FROM oat_graph_node WHERE baseline_id = ? AND invalidated_at IS NULL
                AND (locator = ? OR locator LIKE ? || ':%') ORDER BY CASE WHEN locator = ? THEN 0 ELSE 1 END LIMIT 1
                """, this::node, baselineId, locator, locator, locator).stream().findFirst();
    }
    public List<GraphNode> findNodes(String baselineId, String focusId, int depth, int maxNodes) {
        if (focusId == null || focusId.isBlank()) {
            return jdbc.query("""
                    SELECT * FROM oat_graph_node WHERE baseline_id = ? AND invalidated_at IS NULL
                    ORDER BY node_kind, display_name LIMIT ?
                    """, this::node, baselineId, maxNodes);
        }
        return jdbc.query("""
                WITH RECURSIVE walk(id, level) AS (
                  SELECT id, 0 FROM oat_graph_node WHERE baseline_id = ? AND id = ? AND invalidated_at IS NULL
                  UNION
                  SELECT CASE WHEN e.source_node_id = w.id THEN e.target_node_id ELSE e.source_node_id END, w.level + 1
                  FROM walk w JOIN oat_graph_edge e ON (e.source_node_id = w.id OR e.target_node_id = w.id)
                  WHERE e.baseline_id = ? AND e.invalidated_at IS NULL AND w.level < ?
                ) SELECT DISTINCT n.* FROM oat_graph_node n JOIN walk w ON w.id = n.id
                WHERE n.invalidated_at IS NULL ORDER BY n.node_kind, n.display_name LIMIT ?
                """, this::node, baselineId, focusId, baselineId, depth, maxNodes);
    }

    public List<GraphEdge> findEdges(String baselineId, List<String> nodeIds, int maxEdges) {
        if (nodeIds.isEmpty()) return List.of();
        return jdbc.query("""
                SELECT * FROM oat_graph_edge WHERE baseline_id = ? AND invalidated_at IS NULL
                AND source_node_id = ANY (?) AND target_node_id = ANY (?) ORDER BY edge_type LIMIT ?
                """,
                this::edge, baselineId, nodeIds.toArray(String[]::new), nodeIds.toArray(String[]::new), maxEdges);
    }

    private GraphAggregate aggregate(ResultSet rs, int row) throws SQLException {
        return new GraphAggregate(rs.getString("id"), rs.getString("baseline_id"), rs.getString("project_id"),
                rs.getString("aggregate_kind"), rs.getString("subject_id"), rs.getString("source_hash"), map(rs.getString("payload_json")));
    }
    private GraphSnapshot snapshot(ResultSet rs, int row) throws SQLException {
        return new GraphSnapshot(rs.getString("id"), rs.getString("project_id"), rs.getString("baseline_id"),
                rs.getString("repository_url"), rs.getString("source_commit"), SnapshotKind.valueOf(rs.getString("snapshot_kind")),
                rs.getString("analyzer_version"), rs.getString("input_hash"), rs.getString("status"), map(rs.getString("attributes_json")));
    }
    private GraphNode node(ResultSet rs, int row) throws SQLException {
        return new GraphNode(rs.getString("id"), rs.getString("snapshot_id"), rs.getString("baseline_id"), rs.getString("project_id"),
                GraphNodeKind.valueOf(rs.getString("node_kind")), rs.getString("stable_symbol_id"), rs.getString("logical_symbol_id"),
                rs.getString("locator"), rs.getString("display_name"), rs.getString("content_hash"), map(rs.getString("attributes_json")));
    }
    private GraphEdge edge(ResultSet rs, int row) throws SQLException {
        return new GraphEdge(rs.getString("id"), rs.getString("snapshot_id"), rs.getString("baseline_id"), rs.getString("project_id"),
                rs.getString("source_node_id"), rs.getString("target_node_id"), GraphEdgeType.valueOf(rs.getString("edge_type")),
                EvidenceKind.valueOf(rs.getString("evidence_kind")), rs.getString("evidence_level"), rs.getDouble("confidence"),
                rs.getString("execution_id"), rs.getString("evidence_locator"), map(rs.getString("attributes_json")));
    }
    private String json(Map<String, Object> value) { return UtilJson.writeValueAsString(value == null ? Map.of() : value); }
    @SuppressWarnings("unchecked") private Map<String, Object> map(String value) { return value == null ? Map.of() : UtilJson.convertValue(value, Map.class); }

    public record GraphAggregate(String id, String baselineId, String projectId, String kind, String subjectId,
                                 String sourceHash, Map<String, Object> payload) {}
    public record RuntimeExecution(String id, String projectId, String baselineId, String sourceCommit, String externalExecutionId,
                                   String environment, String collectorVersion, String traceHash, String coverageHash,
                                   java.time.OffsetDateTime startedAt, java.time.OffsetDateTime finishedAt,
                                   java.time.OffsetDateTime capturedAt, Map<String, Object> attributes) {}
    public record GraphSnapshot(String id, String projectId, String baselineId, String repositoryUrl, String sourceCommit,
                                SnapshotKind kind, String analyzerVersion, String inputHash, String status, Map<String, Object> attributes) {}
    public record GraphNode(String id, String snapshotId, String baselineId, String projectId, GraphNodeKind kind,
                            String stableSymbolId, String logicalSymbolId, String locator, String displayName,
                            String contentHash, Map<String, Object> attributes) {}
    public record GraphEdge(String id, String snapshotId, String baselineId, String projectId, String sourceNodeId,
                            String targetNodeId, GraphEdgeType type, EvidenceKind evidenceKind, String evidenceLevel,
                            double confidence, String executionId, String evidenceLocator, Map<String, Object> attributes) {}
}
