package com.company.aiops.persistence.repository.jdbc;

import com.company.aiops.llm.client.LlmCallStatus;
import com.company.aiops.llm.service.LlmCallLog;
import com.company.aiops.persistence.repository.LlmCallLogRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * JDBC repository for {@code aiops_llm_call_log}.
 */
@Repository
public class JdbcLlmCallLogRepository implements LlmCallLogRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcLlmCallLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(LlmCallLog llmCallLog) {
        jdbcTemplate.update(
                """
                        INSERT INTO aiops_llm_call_log (
                            task_id, alert_id, provider, model_name, request_json, response_text,
                            input_tokens, output_tokens, latency_ms, call_status, error_message,
                            expire_at, created_at
                        ) VALUES (?, ?, ?, ?, CAST(? AS JSON), ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                llmCallLog.taskId(),
                llmCallLog.alertId(),
                llmCallLog.provider(),
                llmCallLog.modelName(),
                llmCallLog.requestJson(),
                llmCallLog.responseText(),
                llmCallLog.inputTokens(),
                llmCallLog.outputTokens(),
                llmCallLog.latency() == null ? null : llmCallLog.latency().toMillis(),
                llmCallLog.callStatus().name(),
                llmCallLog.errorMessage(),
                timestamp(llmCallLog.expireAt()),
                timestamp(llmCallLog.createdAt())
        );
    }

    @Override
    public List<LlmCallLog> findByTaskId(String taskId) {
        return jdbcTemplate.query(
                """
                        SELECT task_id, alert_id, provider, model_name, request_json, response_text,
                               input_tokens, output_tokens, latency_ms, call_status, error_message,
                               expire_at, created_at
                        FROM aiops_llm_call_log
                        WHERE task_id = ?
                        ORDER BY created_at ASC, id ASC
                        """,
                (rs, rowNum) -> new LlmCallLog(
                        rs.getString("task_id"),
                        rs.getString("alert_id"),
                        rs.getString("provider"),
                        rs.getString("model_name"),
                        rs.getString("request_json"),
                        rs.getString("response_text"),
                        rs.getObject("input_tokens") == null ? null : rs.getInt("input_tokens"),
                        rs.getObject("output_tokens") == null ? null : rs.getInt("output_tokens"),
                        rs.getObject("latency_ms") == null ? null : Duration.ofMillis(rs.getLong("latency_ms")),
                        LlmCallStatus.valueOf(rs.getString("call_status")),
                        rs.getString("error_message"),
                        instant(rs.getTimestamp("expire_at")),
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
