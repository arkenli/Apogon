package com.company.aiops.provider.query.mapping.model;

import java.time.Instant;

public record ProviderQueryMappingItemResponse(
        long id,
        String serviceName,
        String alertClusterName,
        String providerName,
        String queryServiceName,
        String queryClusterName,
        String metadataJson,
        boolean enabled,
        String createdBy,
        String updatedBy,
        Instant createdAt,
        Instant updatedAt
) {
}
