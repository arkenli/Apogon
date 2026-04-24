package com.company.aiops.provider.model;

import java.util.List;

/**
 * Resource inventory resolved for one service and cluster pair.
 */
public record ResourceMapping(
        String serviceName,
        String clusterName,
        List<ResourceMappingItem> resources
) {
}
