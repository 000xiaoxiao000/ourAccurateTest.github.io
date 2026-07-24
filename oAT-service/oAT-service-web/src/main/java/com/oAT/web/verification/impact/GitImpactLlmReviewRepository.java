package com.oAT.web.verification.impact;

import com.fasterxml.jackson.core.type.TypeReference;
import com.oAT.web.common.UtilJson;
import com.oAT.web.verification.impact.GitImpactAnalysisService.LlmReviewProgress;
import com.oAT.web.verification.impact.GitImpactAnalysisService.LlmReviewStatus;
import com.oAT.web.verification.impact.ImpactModels.LlmJudgement;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Repository
public class GitImpactLlmReviewRepository {
    private static final TypeReference<List<LlmJudgement>> JUDGEMENTS = new TypeReference<>() { };
    private final JdbcTemplate jdbc;

    public GitImpactLlmReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(LlmReviewProgress progress) {
        jdbc.update("""
                INSERT INTO oat_git_impact_llm_review
                (report_id, status, total, completed, judgements_json, message, update_time)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (report_id) DO UPDATE SET
                    status = EXCLUDED.status, total = EXCLUDED.total, completed = EXCLUDED.completed,
                    judgements_json = EXCLUDED.judgements_json, message = EXCLUDED.message,
                    update_time = CURRENT_TIMESTAMP
                """, progress.reportId(), progress.status().name(), progress.total(), progress.completed(),
                UtilJson.writeValueAsString(progress.judgements()), progress.message());
    }

    public Optional<LlmReviewProgress> find(String reportId) {
        return jdbc.query("SELECT * FROM oat_git_impact_llm_review WHERE report_id = ?", (rs, rowNum) -> {
            try {
                List<LlmJudgement> judgements = UtilJson.getObjectMapper().readValue(rs.getString("judgements_json"), JUDGEMENTS);
                return new LlmReviewProgress(reportId, LlmReviewStatus.valueOf(rs.getString("status")),
                        rs.getInt("total"), rs.getInt("completed"), judgements, rs.getString("message"));
            } catch (IOException exception) {
                throw new IllegalStateException("Git 影响 LLM 审阅结果无法读取: " + reportId, exception);
            }
        }, reportId).stream().findFirst();
    }
}
