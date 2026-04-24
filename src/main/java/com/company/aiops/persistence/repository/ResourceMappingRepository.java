package com.company.aiops.persistence.repository;

import com.company.aiops.provider.model.ResourceMapping;

/**
 * Reads seeded service-to-resource mappings from storage.
 */
public interface ResourceMappingRepository {

    /**
     * Returns enabled mappings for the given service and cluster pair.
     */
    ResourceMapping findEnabledByServiceAndCluster(String serviceName, String clusterName);
}
