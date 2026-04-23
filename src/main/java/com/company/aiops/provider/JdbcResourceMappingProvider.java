package com.company.aiops.provider;

import com.company.aiops.persistence.ResourceMappingRepository;
import org.springframework.stereotype.Component;

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
