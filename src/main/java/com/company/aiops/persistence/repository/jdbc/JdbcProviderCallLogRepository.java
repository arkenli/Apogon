package com.company.aiops.persistence.repository.jdbc;

import com.company.aiops.persistence.repository.ProviderCallLogRepository;
import com.company.aiops.provider.model.ProviderCallLog;
import com.company.aiops.provider.model.ProviderStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * JDBC implementation for provider invocation audit logs.
 */
@Repository
public class JdbcProviderCallLogRepository implements ProviderCallLogRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcProviderCallLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(ProviderCallLog providerCallLog) {
        jdbcTemplate.update(
                """
                        INSERT INTO aiops_provider_call_log (
                            task_id, alert_id, provider_name, provider_status, critical,
                            latency_ms, error_code, error_message, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                providerCallLog.taskId(),
                providerCallLog.alertId(),
                providerCallLog.providerName(),
                providerCallLog.providerStatus().name(),
                providerCallLog.critical(),
                providerCallLog.latency() == null ? null : providerCallLog.latency().toMillis(),
                providerCallLog.errorCode(),
                providerCallLog.errorMessage(),
                timestamp(providerCallLog.createdAt())
        );
    }

    @Override
    public List<ProviderCallLog> findByTaskId(String taskId) {
        return jdbcTemplate.query(
                """
                        SELECT task_id, alert_id, provider_name, provider_status, critical,
                               latency_ms, error_code, error_message, created_at
                        FROM aiops_provider_call_log
                        WHERE task_id = ?
                        ORDER BY created_at ASC, id ASC
                        """,
                (rs, rowNum) -> new ProviderCallLog(
                        rs.getString("task_id"),
                        rs.getString("alert_id"),
                        rs.getString("provider_name"),
                        ProviderStatus.valueOf(rs.getString("provider_status")),
                        rs.getBoolean("critical"),
                        rs.getObject("latency_ms") == null
                                ? null
                                : Duration.ofMillis(rs.getLong("latency_ms")),
                        rs.getString("error_code"),
                        rs.getString("error_message"),
                        instant(rs.getTimestamp("created_at"))
                ),
                taskId
        );
    }

    private Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
