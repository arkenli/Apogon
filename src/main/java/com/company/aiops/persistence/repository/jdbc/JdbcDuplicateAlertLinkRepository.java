package com.company.aiops.persistence.repository.jdbc;

import com.company.aiops.persistence.repository.DuplicateAlertLinkRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * JDBC implementation for duplicate-alert audit records.
 */
@Repository
public class JdbcDuplicateAlertLinkRepository implements DuplicateAlertLinkRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcDuplicateAlertLinkRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(String sourceAlertId, String targetTaskId, String dedupeKey, Instant createdAt) {
        jdbcTemplate.update(
                """
                        INSERT INTO aiops_duplicate_alert_link (
                            source_alert_id, target_task_id, dedupe_key, created_at
                        ) VALUES (?, ?, ?, ?)
                        """,
                sourceAlertId,
                targetTaskId,
                dedupeKey,
                Timestamp.from(createdAt)
        );
    }
}
