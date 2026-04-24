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

    /**
     * Creates an explicit empty mapping so later stages can distinguish "no mapping configured"
     * from "provider returned null".
     */
    public static ResourceMapping empty(String serviceName, String clusterName) {
        return new ResourceMapping(serviceName, clusterName, List.of());
    }

    /**
     * Returns resource ids for one resource type, for example {@code DATABASE}, {@code DCS}, or
     * {@code MQ}. The lookup is case-sensitive because the seed data uses stable uppercase values.
     */
    public List<String> resourceIds(String resourceType) {
        if (resourceType == null || resources == null || resources.isEmpty()) {
            return List.of();
        }
        return resources.stream()
                .filter(item -> resourceType.equals(item.resourceType()))
                .map(ResourceMappingItem::resourceId)
                .toList();
    }
}
