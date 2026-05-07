package com.company.aiops.resource.mapping.model;

import java.time.Instant;

/**
 * One stored row from aiops_resource_mapping.
 */
public record ResourceMappingRecord(
        Long id,
        String serviceName,
        String clusterName,
        String resourceType,
        String resourceId,
        String resourceName,
        boolean enabled,
        String metadataJson,
        String createdBy,
        String updatedBy,
        Instant createdAt,
        Instant updatedAt
) {
}
