package com.company.aiops.resource.mapping.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResourceMappingItemUpsertRequest(
        @NotBlank @Size(max = 256) String serviceName,
        @NotBlank @Size(max = 128) String clusterName,
        @NotBlank @Size(max = 64) String resourceType,
        @NotBlank @Size(max = 256) String resourceId,
        @Size(max = 256) String resourceName,
        String metadataJson
) {
}
