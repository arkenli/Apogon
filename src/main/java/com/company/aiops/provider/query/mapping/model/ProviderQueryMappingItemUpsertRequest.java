package com.company.aiops.provider.query.mapping.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProviderQueryMappingItemUpsertRequest(
        @NotBlank @Size(max = 256) String serviceName,
        @NotBlank @Size(max = 128) String alertClusterName,
        @NotBlank @Size(max = 128) String providerName,
        @NotBlank @Size(max = 256) String queryServiceName,
        @NotBlank @Size(max = 128) String queryClusterName,
        String metadataJson
) {
}
