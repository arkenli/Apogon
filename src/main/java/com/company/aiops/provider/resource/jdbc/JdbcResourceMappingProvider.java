package com.company.aiops.provider.resource.jdbc;

import com.company.aiops.persistence.repository.ResourceMappingRepository;
import com.company.aiops.provider.model.ResourceMapping;
import com.company.aiops.provider.resource.ResourceMappingProvider;
import org.springframework.stereotype.Component;

/**
 * Resource mapping provider backed by the seeded MySQL mapping table.
 */
@Component
public class JdbcResourceMappingProvider implements ResourceMappingProvider {

    private final ResourceMappingRepository resourceMappingRepository;

    public JdbcResourceMappingProvider(ResourceMappingRepository resourceMappingRepository) {
        this.resourceMappingRepository = resourceMappingRepository;
    }

    @Override
    public ResourceMapping queryResourceMapping(String serviceName, String clusterName) {
        return resourceMappingRepository.findEnabledByServiceAndCluster(serviceName, clusterName);
    }
}
