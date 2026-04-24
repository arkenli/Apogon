package com.company.aiops.provider.model;

/**
 * One concrete infrastructure resource related to a service.
 */
public record ResourceMappingItem(
        String resourceType,
        String resourceId,
        String resourceName,
        String metadataJson
) {
}
