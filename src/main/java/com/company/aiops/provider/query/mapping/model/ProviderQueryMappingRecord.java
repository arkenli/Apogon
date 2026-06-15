package com.company.aiops.provider.query.mapping.model;

import java.time.Instant;

/**
 * One stored row from aiops_provider_query_mapping.
 */
public record ProviderQueryMappingRecord(
        Long id,
        String serviceName,
        String alertClusterName,
        String providerName,
        String queryServiceName,
        String queryClusterName,
        boolean enabled,
        String metadataJson,
        String createdBy,
        String updatedBy,
        Instant createdAt,
        Instant updatedAt
) {
}
