package com.company.aiops.provider.query;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Lookup wrapper for provider query targets resolved for one alert.
 */
public record ProviderQueryTargets(
        String alertServiceName,
        String alertClusterName,
        Map<String, ProviderQueryTarget> targets
) {

    public ProviderQueryTargets {
        if (alertServiceName == null || alertServiceName.isBlank()) {
            throw new IllegalArgumentException("alertServiceName must not be blank");
        }
        if (alertClusterName == null || alertClusterName.isBlank()) {
            throw new IllegalArgumentException("alertClusterName must not be blank");
        }
        targets = targets == null ? Map.of() : Map.copyOf(targets);
    }

    public static ProviderQueryTargets fallback(
            String alertServiceName,
            String alertClusterName,
            Set<String> providerNames
    ) {
        Map<String, ProviderQueryTarget> fallbackTargets = new LinkedHashMap<>();
        for (String providerName : providerNames) {
            fallbackTargets.put(
                    providerName,
                    ProviderQueryTarget.fallback(providerName, alertServiceName, alertClusterName)
            );
        }
        return new ProviderQueryTargets(alertServiceName, alertClusterName, fallbackTargets);
    }

    public ProviderQueryTarget targetFor(String providerName) {
        requireText(providerName, "providerName");
        ProviderQueryTarget target = targets.get(providerName);
        if (target != null) {
            return target;
        }
        return ProviderQueryTarget.fallback(providerName, alertServiceName, alertClusterName);
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
