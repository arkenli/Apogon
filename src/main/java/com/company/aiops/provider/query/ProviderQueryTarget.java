package com.company.aiops.provider.query;

/**
 * Provider-specific service and cluster values used for external lookups.
 */
public record ProviderQueryTarget(
        String providerName,
        String serviceName,
        String clusterName,
        boolean mapped
) {

    public ProviderQueryTarget {
        requireText(providerName, "providerName");
        requireText(serviceName, "serviceName");
        requireText(clusterName, "clusterName");
    }

    public static ProviderQueryTarget mapped(String providerName, String serviceName, String clusterName) {
        return new ProviderQueryTarget(providerName, serviceName, clusterName, true);
    }

    public static ProviderQueryTarget fallback(String providerName, String serviceName, String clusterName) {
        return new ProviderQueryTarget(providerName, serviceName, clusterName, false);
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
