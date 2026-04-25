package com.company.aiops.persistence.repository.jdbc;

import com.company.aiops.analysis.bundle.StoredEvidenceBundle;
import com.company.aiops.common.json.JsonMapper;
import com.company.aiops.persistence.repository.EvidenceBundleRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * JDBC persistence for structured evidence bundles.
 */
@Repository
public class JdbcEvidenceBundleRepository implements EvidenceBundleRepository {

    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;

    public JdbcEvidenceBundleRepository(JdbcTemplate jdbcTemplate, JsonMapper jsonMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void save(StoredEvidenceBundle<?> evidenceBundle) {
        jdbcTemplate.update(
                """
                        INSERT INTO aiops_evidence_bundle (
                            task_id, alert_id, evidence_type, evidence_json, expire_at, created_at
                        ) VALUES (?, ?, ?, CAST(? AS JSON), ?, ?)
                        """,
                evidenceBundle.taskId(),
                evidenceBundle.alertId(),
                evidenceBundle.evidenceType().storageValue(),
                jsonMapper.toJson(evidenceBundle.payload()),
                timestamp(evidenceBundle.expireAt()),
                timestamp(evidenceBundle.createdAt())
        );
    }

    private Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
