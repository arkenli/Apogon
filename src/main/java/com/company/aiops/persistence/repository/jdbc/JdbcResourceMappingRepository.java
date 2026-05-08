package com.company.aiops.persistence.repository.jdbc;

import com.company.aiops.persistence.repository.ResourceMappingRepository;
import com.company.aiops.provider.model.ResourceMapping;
import com.company.aiops.provider.model.ResourceMappingItem;
import com.company.aiops.resource.mapping.model.ResourceMappingRecord;
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
 * JDBC repository that reads resource mapping seed data.
 */
@Repository
public class JdbcResourceMappingRepository implements ResourceMappingRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcResourceMappingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public ResourceMapping findEnabledByServiceAndCluster(String serviceName, String clusterName) {
        List<ResourceMappingItem> resources = jdbcTemplate.query(
                """
                        SELECT resource_type, resource_id, resource_name, metadata_json
                        FROM aiops_resource_mapping
                        WHERE service_name = ? AND cluster_name = ? AND enabled = 1
                        ORDER BY resource_type, resource_id
                        """,
                (rs, rowNum) -> new ResourceMappingItem(
                        rs.getString("resource_type"),
                        rs.getString("resource_id"),
                        rs.getString("resource_name"),
                        rs.getString("metadata_json")
                ),
                serviceName,
                clusterName
        );
        return new ResourceMapping(serviceName, clusterName, resources);
    }

    @Override
    public List<ResourceMappingRecord> findAll(String serviceName, String clusterName) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, service_name, cluster_name, resource_type, resource_id, resource_name,
                       enabled, metadata_json, created_by, updated_by, created_at, updated_at
                FROM aiops_resource_mapping
                WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (hasText(serviceName)) {
            sql.append(" AND service_name = ?");
            args.add(serviceName);
        }
        if (hasText(clusterName)) {
            sql.append(" AND cluster_name = ?");
            args.add(clusterName);
        }
        sql.append(" ORDER BY service_name, cluster_name, resource_type, resource_id");
        return jdbcTemplate.query(sql.toString(), this::mapRecord, args.toArray());
    }

    @Override
    public Optional<ResourceMappingRecord> findById(long id) {
        List<ResourceMappingRecord> records = jdbcTemplate.query(
                """
                        SELECT id, service_name, cluster_name, resource_type, resource_id, resource_name,
                               enabled, metadata_json, created_by, updated_by, created_at, updated_at
                        FROM aiops_resource_mapping
                        WHERE id = ?
                        """,
                this::mapRecord,
                id
        );
        return records.stream().findFirst();
    }

    @Override
    public ResourceMappingRecord create(ResourceMappingRecord record) {
        Instant now = Instant.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    """
                            INSERT INTO aiops_resource_mapping (
                                service_name, cluster_name, resource_type, resource_id, resource_name,
                                enabled, metadata_json, created_by, updated_by, created_at, updated_at
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    Statement.RETURN_GENERATED_KEYS
            );
            statement.setString(1, record.serviceName());
            statement.setString(2, record.clusterName());
            statement.setString(3, record.resourceType());
            statement.setString(4, record.resourceId());
            statement.setString(5, record.resourceName());
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
            throw new IllegalStateException("Failed to create resource mapping row: generated id is null");
        }
        return findById(key.longValue())
                .orElseThrow(() -> new IllegalStateException("Created resource mapping row cannot be reloaded"));
    }

    @Override
    public ResourceMappingRecord update(ResourceMappingRecord record) {
        Instant now = Instant.now();
        jdbcTemplate.update(
                """
                        UPDATE aiops_resource_mapping
                        SET service_name = ?,
                            cluster_name = ?,
                            resource_type = ?,
                            resource_id = ?,
                            resource_name = ?,
                            enabled = ?,
                            metadata_json = ?,
                            updated_by = ?,
                            updated_at = ?
                        WHERE id = ?
                        """,
                record.serviceName(),
                record.clusterName(),
                record.resourceType(),
                record.resourceId(),
                record.resourceName(),
                record.enabled(),
                record.metadataJson(),
                record.updatedBy(),
                timestamp(now),
                record.id()
        );
        return findById(record.id())
                .orElseThrow(() -> new IllegalStateException("Updated resource mapping row cannot be reloaded"));
    }

    @Override
    public boolean deleteById(long id) {
        return jdbcTemplate.update("DELETE FROM aiops_resource_mapping WHERE id = ?", id) > 0;
    }

    private ResourceMappingRecord mapRecord(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ResourceMappingRecord(
                rs.getLong("id"),
                rs.getString("service_name"),
                rs.getString("cluster_name"),
                rs.getString("resource_type"),
                rs.getString("resource_id"),
                rs.getString("resource_name"),
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
