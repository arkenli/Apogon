package com.company.aiops.persistence.repository.jdbc;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.analysis.job.AnalysisJobStatus;
import com.company.aiops.analysis.query.model.AnalysisQueryProjection;
import com.company.aiops.analysis.result.AnalysisResult;
import com.company.aiops.common.json.JsonMapper;
import com.company.aiops.persistence.repository.AnalysisQueryRepository;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JDBC implementation of the phase-9 query repository.
 *
 * <p>The SQL joins task state, stored alert dimensions, final result JSON, and duplicate-alert
 * mappings. This keeps query-specific cross-table logic out of the write repositories that are used
 * by the workflow itself.</p>
 */
@Repository
public class JdbcAnalysisQueryRepository implements AnalysisQueryRepository {

    private static final String BASE_SELECT = """
            SELECT t.task_id,
                   t.alert_id,
                   t.task_status,
                   t.category,
                   t.dedupe_key,
                   t.created_at,
                   t.started_at,
                   t.finished_at,
                   t.current_step,
                   t.status_reason,
                   t.failure_reason,
                   a.service_name,
                   a.interface_name,
                   a.cluster_name,
                   r.result_json
            FROM aiops_analysis_task t
            JOIN aiops_alert_event a ON a.alert_id = t.alert_id
            LEFT JOIN aiops_analysis_result r ON r.task_id = t.task_id
            """;

    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;

    public JdbcAnalysisQueryRepository(JdbcTemplate jdbcTemplate, JsonMapper jsonMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public Optional<AnalysisQueryProjection> findByTaskId(String taskId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    BASE_SELECT + " WHERE t.task_id = ?",
                    this::mapRow,
                    taskId
            ));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<AnalysisQueryProjection> findLatestByAlertId(String alertId) {
        Optional<AnalysisQueryProjection> directMatch = findDirectByAlertId(alertId);
        if (directMatch.isPresent()) {
            return directMatch;
        }

        List<AnalysisQueryProjection> duplicateMatches = jdbcTemplate.query(
                BASE_SELECT + """
                         JOIN aiops_duplicate_alert_link d ON d.target_task_id = t.task_id
                        WHERE d.source_alert_id = ?
                        ORDER BY d.created_at DESC
                        LIMIT 1
                        """,
                this::mapRow,
                alertId
        );
        return duplicateMatches.stream().findFirst();
    }

    @Override
    public List<AnalysisQueryProjection> findHistoryByServiceAndInterface(String serviceName, String interfaceName, int limit) {
        return jdbcTemplate.query(
                BASE_SELECT + """
                        WHERE a.service_name = ?
                          AND a.interface_name = ?
                          AND t.task_status IN ('SUCCESS', 'DEGRADED_SUCCESS')
                          AND r.task_id IS NOT NULL
                        ORDER BY r.generated_at DESC
                        LIMIT ?
                        """,
                this::mapRow,
                serviceName,
                interfaceName,
                limit
        );
    }

    private Optional<AnalysisQueryProjection> findDirectByAlertId(String alertId) {
        List<AnalysisQueryProjection> directMatches = jdbcTemplate.query(
                BASE_SELECT + """
                        WHERE t.alert_id = ?
                        ORDER BY t.created_at DESC
                        LIMIT 1
                        """,
                this::mapRow,
                alertId
        );
        return directMatches.stream().findFirst();
    }

    private AnalysisQueryProjection mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new AnalysisQueryProjection(
                rs.getString("task_id"),
                rs.getString("alert_id"),
                AnalysisJobStatus.valueOf(rs.getString("task_status")),
                AlertCategory.valueOf(rs.getString("category")),
                rs.getString("service_name"),
                rs.getString("interface_name"),
                rs.getString("cluster_name"),
                rs.getString("dedupe_key"),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("finished_at")),
                rs.getString("current_step"),
                rs.getString("status_reason"),
                rs.getString("failure_reason"),
                jsonMapper.fromJson(rs.getString("result_json"), AnalysisResult.class)
        );
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
