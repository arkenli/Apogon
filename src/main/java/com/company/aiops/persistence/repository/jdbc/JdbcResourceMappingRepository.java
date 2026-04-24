package com.company.aiops.persistence.repository.jdbc;

import com.company.aiops.persistence.repository.ResourceMappingRepository;
import com.company.aiops.provider.model.ResourceMapping;
import com.company.aiops.provider.model.ResourceMappingItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

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
}
