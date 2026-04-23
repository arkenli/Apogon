package com.company.aiops.persistence;

import com.company.aiops.provider.ResourceMapping;

public interface ResourceMappingRepository {

    ResourceMapping findEnabledByServiceAndCluster(String serviceName, String clusterName);
}
