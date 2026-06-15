package com.company.aiops.provider.query;

import java.util.Set;

/**
 * Resolves provider-specific query targets for one alert identity.
 */
public interface ProviderQueryTargetResolver {

    ProviderQueryTargets resolveAll(String serviceName, String alertClusterName, Set<String> providerNames);
}
