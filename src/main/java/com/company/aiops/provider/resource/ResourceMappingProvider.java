package com.company.aiops.provider.resource;

import com.company.aiops.provider.model.ResourceMapping;

/**
 * Loads the infrastructure resources associated with an alert target.
 */
public interface ResourceMappingProvider {

    /**
     * Returns all enabled resources bound to the given service and cluster.
     */
    ResourceMapping queryResourceMapping(String serviceName, String clusterName);
}
