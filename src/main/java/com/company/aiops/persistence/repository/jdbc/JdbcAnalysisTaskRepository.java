package com.company.aiops.persistence.repository.jdbc;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.analysis.job.AnalysisJob;
import com.company.aiops.analysis.job.AnalysisJobStatus;
import com.company.aiops.persistence.repository.AnalysisTaskRepository;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * JDBC implementation for analysis task persistence and dedupe lookups.
 */
@Repository
public class JdbcAnalysisTaskRepository implements AnalysisTaskRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcAnalysisTaskRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(AnalysisJob analysisJob) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                        INSERT INTO aiops_analysis_task (
                            task_id, alert_id, category, dedupe_key, task_status, current_step,
                            status_reason, retry_count, state_version, failure_reason, created_at,
                            started_at, finished_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                            task_status = VALUES(task_status),
                            current_step = VALUES(current_step),
                            status_reason = VALUES(status_reason),
                            retry_count = VALUES(retry_count),
                            state_version = VALUES(state_version),
                            failure_reason = VALUES(failure_reason),
                            started_at = VALUES(started_at),
                            finished_at = VALUES(finished_at),
                            updated_at = VALUES(updated_at)
                        """,
                analysisJob.taskId(),
                analysisJob.alertId(),
                analysisJob.category().name(),
                analysisJob.dedupeKey(),
                analysisJob.status().name(),
                analysisJob.currentStep(),
                analysisJob.statusReason(),
                analysisJob.retryCount(),
                analysisJob.stateVersion(),
                analysisJob.failureReason(),
                timestamp(analysisJob.createdAt() == null ? now : analysisJob.createdAt()),
                timestamp(analysisJob.startedAt()),
                timestamp(analysisJob.finishedAt()),
                timestamp(now)
        );
    }

    @Override
    public boolean updateState(AnalysisJob analysisJob, long expectedStateVersion) {
        Instant now = Instant.now();
        return jdbcTemplate.update(
                """
                        UPDATE aiops_analysis_task
                        SET task_status = ?,
                            current_step = ?,
                            status_reason = ?,
                            retry_count = ?,
                            state_version = ?,
                            failure_reason = ?,
                            started_at = ?,
                            finished_at = ?,
                            updated_at = ?
                        WHERE task_id = ?
                          AND state_version = ?
                        """,
                analysisJob.status().name(),
                analysisJob.currentStep(),
                analysisJob.statusReason(),
                analysisJob.retryCount(),
                analysisJob.stateVersion(),
                analysisJob.failureReason(),
                timestamp(analysisJob.startedAt()),
                timestamp(analysisJob.finishedAt()),
                timestamp(now),
                analysisJob.taskId(),
                expectedStateVersion
        ) == 1;
    }

    @Override
    public Optional<AnalysisJob> findByTaskId(String taskId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT * FROM aiops_analysis_task WHERE task_id = ?",
                    this::mapRow,
                    taskId
            ));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<AnalysisJob> findLatestByDedupeKeyAndStatusesSince(
            String dedupeKey,
            Collection<AnalysisJobStatus> statuses,
            Instant createdSince
    ) {
        if (statuses == null || statuses.isEmpty()) {
            return Optional.empty();
        }

        StringJoiner placeholders = new StringJoiner(", ");
        List<String> statusNames = statuses.stream().map(Enum::name).toList();
        statusNames.forEach(status -> placeholders.add("?"));

        Object[] args = new Object[statusNames.size() + 2];
        args[0] = dedupeKey;
        for (int i = 0; i < statusNames.size(); i++) {
            args[i + 1] = statusNames.get(i);
        }
        args[args.length - 1] = timestamp(createdSince);

        List<AnalysisJob> matches = jdbcTemplate.query(
                """
                        SELECT *
                        FROM aiops_analysis_task
                        WHERE dedupe_key = ?
                          AND task_status IN (%s)
                          AND created_at >= ?
                        ORDER BY created_at DESC
                        LIMIT 1
                        """.formatted(placeholders),
                this::mapRow,
                args
        );
        return matches.stream().findFirst();
    }

    private AnalysisJob mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new AnalysisJob(
                rs.getString("task_id"),
                rs.getString("alert_id"),
                AlertCategory.valueOf(rs.getString("category")),
                rs.getString("dedupe_key"),
                AnalysisJobStatus.valueOf(rs.getString("task_status")),
                rs.getString("current_step"),
                rs.getString("status_reason"),
                rs.getInt("retry_count"),
                rs.getLong("state_version"),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("finished_at")),
                rs.getString("failure_reason")
        );
    }

    private Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
