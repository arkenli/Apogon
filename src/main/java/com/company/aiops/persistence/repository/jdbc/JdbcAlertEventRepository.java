package com.company.aiops.persistence.repository.jdbc;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.alert.model.AlertEvent;
import com.company.aiops.alert.model.AlertTriggerValues;
import com.company.aiops.common.json.JsonMapper;
import com.company.aiops.persistence.repository.AlertEventRepository;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * JDBC implementation for storing raw alert snapshots.
 */
@Repository
public class JdbcAlertEventRepository implements AlertEventRepository {

    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;

    public JdbcAlertEventRepository(JdbcTemplate jdbcTemplate, JsonMapper jsonMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void save(AlertEvent alertEvent) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                        INSERT INTO aiops_alert_event (
                            alert_id, category, raw_category, status, severity, service_name, interface_name,
                            cluster_name, site, tenant, application_name, alert_name, metric_name, metric_occur_time,
                            received_at, trigger_values_json, raw_text, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                            category = VALUES(category),
                            raw_category = VALUES(raw_category),
                            status = VALUES(status),
                            severity = VALUES(severity),
                            service_name = VALUES(service_name),
                            interface_name = VALUES(interface_name),
                            cluster_name = VALUES(cluster_name),
                            site = VALUES(site),
                            tenant = VALUES(tenant),
                            application_name = VALUES(application_name),
                            alert_name = VALUES(alert_name),
                            metric_name = VALUES(metric_name),
                            metric_occur_time = VALUES(metric_occur_time),
                            received_at = VALUES(received_at),
                            trigger_values_json = VALUES(trigger_values_json),
                            raw_text = VALUES(raw_text),
                            updated_at = VALUES(updated_at)
                        """,
                alertEvent.alertId(),
                alertEvent.category().name(),
                alertEvent.rawCategory(),
                alertEvent.status(),
                alertEvent.severity(),
                alertEvent.serviceName(),
                alertEvent.interfaceName(),
                alertEvent.cluster(),
                alertEvent.site(),
                alertEvent.tenant(),
                alertEvent.application(),
                alertEvent.alertName(),
                alertEvent.metricName(),
                timestamp(alertEvent.metricOccurTime()),
                timestamp(alertEvent.receivedAt()),
                jsonMapper.toJson(alertEvent.triggerValues()),
                alertEvent.rawText(),
                timestamp(now),
                timestamp(now)
        );
    }

    @Override
    public Optional<AlertEvent> findByAlertId(String alertId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT * FROM aiops_alert_event WHERE alert_id = ?",
                    this::mapRow,
                    alertId
            ));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    private AlertEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new AlertEvent(
                rs.getString("alert_id"),
                AlertCategory.valueOf(rs.getString("category")),
                rs.getString("status"),
                rs.getString("severity"),
                rs.getString("service_name"),
                rs.getString("interface_name"),
                rs.getString("cluster_name"),
                rs.getString("site"),
                rs.getString("tenant"),
                rs.getString("application_name"),
                rs.getString("alert_name"),
                rs.getString("metric_name"),
                rs.getString("raw_category"),
                rs.getString("raw_text"),
                instant(rs.getTimestamp("metric_occur_time")),
                instant(rs.getTimestamp("received_at")),
                jsonMapper.fromJson(rs.getString("trigger_values_json"), AlertTriggerValues.class)
        );
    }

    private Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
