package com.oAT.web.verification.graph;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class GraphRepositoryTest {

    @Test
    void saveAggregate_upsertsByStableAggregateIdWhenSourceHashChanges() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        GraphRepository repository = new GraphRepository(jdbc);

        repository.saveAggregate(new GraphRepository.GraphAggregate(
                "rm_quality_gate_summary:stable", "baseline-1", "project-1",
                "RM_QUALITY_GATE_SUMMARY", "baseline-1", "new-source-hash", Map.of("totalCriteria", 2)));

        var sqlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sqlCaptor.capture(), any(), any(), any(), any(), any(), any(), any());
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("ON CONFLICT (id) DO UPDATE"));
        assertTrue(sql.contains("source_hash = EXCLUDED.source_hash"));
    }
}
