package com.company.aiops.resource.mapping.model;

import java.time.Instant;

public record ResourceMappingItemResponse(
        long id,
        String resourceType,
        String resourceId,
        String resourceName,
        String metadataJson,
        boolean enabled,
        String createdBy,
        String updatedBy,
        Instant createdAt,
        Instant updatedAt
) {
}
