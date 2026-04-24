package com.company.aiops.persistence.repository.jdbc;

import com.company.aiops.analysis.model.AnalysisMode;
import com.company.aiops.analysis.model.AnalysisResult;
import com.company.aiops.analysis.model.HistoricalReference;
import com.company.aiops.common.json.JsonMapper;
import com.company.aiops.persistence.repository.AnalysisResultRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JDBC implementation for final analysis results and history projections.
 */
@Repository
public class JdbcAnalysisResultRepository implements AnalysisResultRepository {

    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;

    public JdbcAnalysisResultRepository(JdbcTemplate jdbcTemplate, JsonMapper jsonMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void save(AnalysisResult analysisResult) {
        Instant now = Instant.now();
        jdbcTemplate.update(
                """
                        INSERT INTO aiops_analysis_result (
                            task_id, alert_id, category, analysis_mode, summary, impact_level,
                            impact_description, overall_confidence, llm_used, llm_status, degraded,
                            degraded_by_provider_failure, degraded_reason, rule_engine_version,
                            result_json, generated_at, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), ?, ?)
                        ON DUPLICATE KEY UPDATE
                            analysis_mode = VALUES(analysis_mode),
                            summary = VALUES(summary),
                            impact_level = VALUES(impact_level),
                            impact_description = VALUES(impact_description),
                            overall_confidence = VALUES(overall_confidence),
                            llm_used = VALUES(llm_used),
                            llm_status = VALUES(llm_status),
                            degraded = VALUES(degraded),
                            degraded_by_provider_failure = VALUES(degraded_by_provider_failure),
                            degraded_reason = VALUES(degraded_reason),
                            result_json = VALUES(result_json),
                            generated_at = VALUES(generated_at)
                        """,
                analysisResult.taskId(),
                analysisResult.alertId(),
                analysisResult.category().name(),
                analysisResult.analysisMode().name(),
                analysisResult.summary(),
                analysisResult.impact() == null ? null : analysisResult.impact().level(),
                analysisResult.impact() == null ? null : analysisResult.impact().description(),
                analysisResult.overallConfidence(),
                analysisResult.llmUsed(),
                analysisResult.llmStatus(),
                analysisResult.degraded(),
                analysisResult.degradedByProviderFailure(),
                analysisResult.degradedReason(),
                null,
                jsonMapper.toJson(analysisResult),
                timestamp(analysisResult.generatedAt()),
                timestamp(now)
        );
    }

    @Override
    public Optional<HistoricalReference> findLatestHistoricalReference(
            String dedupeKey,
            Instant historicalSince,
            Instant duplicateBefore
    ) {
        List<HistoricalReference> references = jdbcTemplate.query(
                """
                        SELECT r.alert_id, r.task_id, r.analysis_mode, r.summary,
                               r.overall_confidence, r.generated_at
                        FROM aiops_analysis_task t
                        JOIN aiops_analysis_result r ON r.task_id = t.task_id
                        WHERE t.dedupe_key = ?
                          AND t.task_status IN ('SUCCESS', 'DEGRADED_SUCCESS')
                          AND t.created_at >= ?
                          AND t.created_at < ?
                        ORDER BY r.generated_at DESC
                        LIMIT 1
                        """,
                (rs, rowNum) -> new HistoricalReference(
                        true,
                        rs.getString("alert_id"),
                        rs.getString("task_id"),
                        AnalysisMode.valueOf(rs.getString("analysis_mode")),
                        rs.getString("summary"),
                        rs.getDouble("overall_confidence"),
                        instant(rs.getTimestamp("generated_at"))
                ),
                dedupeKey,
                timestamp(historicalSince),
                timestamp(duplicateBefore)
        );
        return references.stream().findFirst();
    }

    private Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
