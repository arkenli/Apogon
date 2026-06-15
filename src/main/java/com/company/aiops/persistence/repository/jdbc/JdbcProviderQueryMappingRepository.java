package com.company.aiops.persistence.repository.jdbc;

import com.company.aiops.persistence.repository.ProviderQueryMappingRepository;
import com.company.aiops.provider.query.mapping.model.ProviderQueryMappingRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC repository for provider query target mappings.
 */
@Repository
public class JdbcProviderQueryMappingRepository implements ProviderQueryMappingRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcProviderQueryMappingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<ProviderQueryMappingRecord> findEnabledByServiceAndAlertCluster(
            String serviceName,
            String alertClusterName
    ) {
        return jdbcTemplate.query(
                """
                        SELECT id, service_name, alert_cluster_name, provider_name,
                               query_service_name, query_cluster_name, enabled, metadata_json,
                               created_by, updated_by, created_at, updated_at
                        FROM aiops_provider_query_mapping
                        WHERE service_name = ? AND alert_cluster_name = ? AND enabled = 1
                        ORDER BY provider_name
                        """,
                this::mapRecord,
                serviceName,
                alertClusterName
        );
    }

    @Override
    public List<ProviderQueryMappingRecord> findAll(
            String serviceName,
            String alertClusterName,
            String providerName
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, service_name, alert_cluster_name, provider_name,
                       query_service_name, query_cluster_name, enabled, metadata_json,
                       created_by, updated_by, created_at, updated_at
                FROM aiops_provider_query_mapping
                WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (hasText(serviceName)) {
            sql.append(" AND service_name = ?");
            args.add(serviceName);
        }
        if (hasText(alertClusterName)) {
            sql.append(" AND alert_cluster_name = ?");
            args.add(alertClusterName);
        }
        if (hasText(providerName)) {
            sql.append(" AND provider_name = ?");
            args.add(providerName);
        }
        sql.append(" ORDER BY service_name, alert_cluster_name, provider_name");
        return jdbcTemplate.query(sql.toString(), this::mapRecord, args.toArray());
    }

    @Override
    public Optional<ProviderQueryMappingRecord> findById(long id) {
        List<ProviderQueryMappingRecord> records = jdbcTemplate.query(
                """
                        SELECT id, service_name, alert_cluster_name, provider_name,
                               query_service_name, query_cluster_name, enabled, metadata_json,
                               created_by, updated_by, created_at, updated_at
                        FROM aiops_provider_query_mapping
                        WHERE id = ?
                        """,
                this::mapRecord,
                id
        );
        return records.stream().findFirst();
    }

    @Override
    public ProviderQueryMappingRecord create(ProviderQueryMappingRecord record) {
        Instant now = Instant.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    """
                            INSERT INTO aiops_provider_query_mapping (
                                service_name, alert_cluster_name, provider_name,
                                query_service_name, query_cluster_name, enabled, metadata_json,
                                created_by, updated_by, created_at, updated_at
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    Statement.RETURN_GENERATED_KEYS
            );
            statement.setString(1, record.serviceName());
            statement.setString(2, record.alertClusterName());
            statement.setString(3, record.providerName());
            statement.setString(4, record.queryServiceName());
            statement.setString(5, record.queryClusterName());
            statement.setBoolean(6, record.enabled());
            statement.setString(7, record.metadataJson());
            statement.setString(8, record.createdBy());
            statement.setString(9, record.updatedBy());
            statement.setTimestamp(10, timestamp(now));
            statement.setTimestamp(11, timestamp(now));
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Failed to create provider query mapping row: generated id is null");
        }
        return findById(key.longValue())
                .orElseThrow(() -> new IllegalStateException("Created provider query mapping row cannot be reloaded"));
    }

    @Override
    public ProviderQueryMappingRecord update(ProviderQueryMappingRecord record) {
        Instant now = Instant.now();
        jdbcTemplate.update(
                """
                        UPDATE aiops_provider_query_mapping
                        SET service_name = ?,
                            alert_cluster_name = ?,
                            provider_name = ?,
                            query_service_name = ?,
                            query_cluster_name = ?,
                            enabled = ?,
                            metadata_json = ?,
                            updated_by = ?,
                            updated_at = ?
                        WHERE id = ?
                        """,
                record.serviceName(),
                record.alertClusterName(),
                record.providerName(),
                record.queryServiceName(),
                record.queryClusterName(),
                record.enabled(),
                record.metadataJson(),
                record.updatedBy(),
                timestamp(now),
                record.id()
        );
        return findById(record.id())
                .orElseThrow(() -> new IllegalStateException("Updated provider query mapping row cannot be reloaded"));
    }

    @Override
    public boolean deleteById(long id) {
        return jdbcTemplate.update("DELETE FROM aiops_provider_query_mapping WHERE id = ?", id) > 0;
    }

    private ProviderQueryMappingRecord mapRecord(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new ProviderQueryMappingRecord(
                rs.getLong("id"),
                rs.getString("service_name"),
                rs.getString("alert_cluster_name"),
                rs.getString("provider_name"),
                rs.getString("query_service_name"),
                rs.getString("query_cluster_name"),
                rs.getBoolean("enabled"),
                rs.getString("metadata_json"),
                rs.getString("created_by"),
                rs.getString("updated_by"),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at"))
        );
    }

    private Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
