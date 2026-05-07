package com.company.aiops.resource.mapping.model;

import java.util.List;

public record ResourceMappingGroupResponse(
        String serviceName,
        String clusterName,
        List<ResourceMappingItemResponse> items
) {
}
