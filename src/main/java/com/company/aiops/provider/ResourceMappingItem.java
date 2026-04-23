package com.company.aiops.provider;

public record ResourceMappingItem(
        String resourceType,
        String resourceId,
        String resourceName,
        String metadataJson
) {
}
