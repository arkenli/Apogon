package com.company.aiops.persistence.repository.jdbc;

import com.company.aiops.analysis.job.AnalysisJobStateLog;
import com.company.aiops.analysis.job.AnalysisJobStatus;
import com.company.aiops.persistence.repository.AnalysisJobStateLogRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * JDBC implementation for the task lifecycle audit trail.
 */
@Repository
public class JdbcAnalysisJobStateLogRepository implements AnalysisJobStateLogRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcAnalysisJobStateLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(AnalysisJobStateLog stateLog) {
        jdbcTemplate.update(
                """
                        INSERT INTO aiops_analysis_job_state_log (
                            task_id, alert_id, from_status, to_status, step_name, reason,
                            state_version, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                stateLog.taskId(),
                stateLog.alertId(),
                stateLog.fromStatus() == null ? null : stateLog.fromStatus().name(),
                stateLog.toStatus().name(),
                stateLog.stepName(),
                stateLog.reason(),
                stateLog.stateVersion(),
                timestamp(stateLog.createdAt())
        );
    }

    @Override
    public List<AnalysisJobStateLog> findByTaskId(String taskId) {
        return jdbcTemplate.query(
                """
                        SELECT task_id, alert_id, from_status, to_status, step_name, reason,
                               state_version, created_at
                        FROM aiops_analysis_job_state_log
                        WHERE task_id = ?
                        ORDER BY state_version ASC, created_at ASC
                        """,
                (rs, rowNum) -> new AnalysisJobStateLog(
                        rs.getString("task_id"),
                        rs.getString("alert_id"),
                        valueOfNullable(rs.getString("from_status")),
                        AnalysisJobStatus.valueOf(rs.getString("to_status")),
                        rs.getString("step_name"),
                        rs.getString("reason"),
                        rs.getLong("state_version"),
                        instant(rs.getTimestamp("created_at"))
                ),
                taskId
        );
    }

    private AnalysisJobStatus valueOfNullable(String status) {
        return status == null ? null : AnalysisJobStatus.valueOf(status);
    }

    private Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
