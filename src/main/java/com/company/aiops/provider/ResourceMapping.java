package com.company.aiops.provider;

import java.util.List;

public record ResourceMapping(
        String serviceName,
        String clusterName,
        List<ResourceMappingItem> resources
) {
}
