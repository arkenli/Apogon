package com.company.aiops.persistence.repository;

import com.company.aiops.provider.model.ResourceMapping;
import com.company.aiops.resource.mapping.model.ResourceMappingRecord;

import java.util.List;
import java.util.Optional;

/**
 * Reads seeded service-to-resource mappings from storage.
 */
public interface ResourceMappingRepository {

    /**
     * Returns enabled mappings for the given service and cluster pair.
     */
    ResourceMapping findEnabledByServiceAndCluster(String serviceName, String clusterName);

    /**
     * Returns all mapping rows filtered by service and cluster when provided.
     */
    List<ResourceMappingRecord> findAll(String serviceName, String clusterName);

    /**
     * Loads one mapping row by primary key.
     */
    Optional<ResourceMappingRecord> findById(long id);

    /**
     * Persists one new mapping row.
     */
    ResourceMappingRecord create(ResourceMappingRecord record);

    /**
     * Updates one existing mapping row.
     */
    ResourceMappingRecord update(ResourceMappingRecord record);

    /**
     * Physically deletes one mapping row by primary key.
     */
    boolean deleteById(long id);
}
