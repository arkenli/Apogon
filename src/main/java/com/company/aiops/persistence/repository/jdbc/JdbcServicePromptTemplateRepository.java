package com.company.aiops.persistence.repository.jdbc;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.llm.prompt.model.ServicePromptTemplateRecord;
import com.company.aiops.persistence.repository.ServicePromptTemplateRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JDBC repository for service-specific LLM prompt templates.
 */
@Repository
public class JdbcServicePromptTemplateRepository implements ServicePromptTemplateRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcServicePromptTemplateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<ServicePromptTemplateRecord> findEnabledByServiceNameAndAlertCategory(
            String serviceName,
            AlertCategory alertCategory
    ) {
        List<ServicePromptTemplateRecord> records = jdbcTemplate.query(
                """
                        SELECT id, service_name, alert_category, system_prompt, user_prompt,
                               enabled, version, metadata_json, created_by, updated_by,
                               created_at, updated_at
                        FROM aiops_service_prompt_template
                        WHERE service_name = ? AND alert_category = ? AND enabled = 1
                        """,
                this::mapRecord,
                serviceName,
                alertCategory.name()
        );
        return records.stream().findFirst();
    }

    private ServicePromptTemplateRecord mapRecord(ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new ServicePromptTemplateRecord(
                rs.getLong("id"),
                rs.getString("service_name"),
                AlertCategory.valueOf(rs.getString("alert_category")),
                rs.getString("system_prompt"),
                rs.getString("user_prompt"),
                rs.getBoolean("enabled"),
                rs.getString("version"),
                rs.getString("metadata_json"),
                rs.getString("created_by"),
                rs.getString("updated_by"),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at"))
        );
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
