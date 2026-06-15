package com.company.aiops.provider;

import java.util.Set;

/**
 * Stable provider names used in provider call logs, query mapping, and orchestration.
 */
public final class ProviderNames {

    public static final String RESOURCE_MAPPING = "resourceMapping";
    public static final String BASELINE = "baseline";
    public static final String INTERFACE_LOG = "interfaceLog";
    public static final String TRACE = "trace";
    public static final String RUNTIME_LOG = "runtimeLog";
    public static final String METRIC_CONTAINER = "metric.container";
    public static final String METRIC_CSE = "metric.cse";
    public static final String METRIC_DATABASE = "metric.database";
    public static final String METRIC_DCS = "metric.dcs";
    public static final String METRIC_MQ = "metric.mq";
    public static final String PROVIDER_QUERY_MAPPING = "provider.queryMapping";

    private static final Set<String> ALL = Set.of(
            RESOURCE_MAPPING,
            BASELINE,
            INTERFACE_LOG,
            TRACE,
            RUNTIME_LOG,
            METRIC_CONTAINER,
            METRIC_CSE,
            METRIC_DATABASE,
            METRIC_DCS,
            METRIC_MQ,
            PROVIDER_QUERY_MAPPING
    );

    private static final Set<String> QUERY_MAPPABLE = Set.of(
            BASELINE,
            METRIC_CONTAINER,
            METRIC_CSE
    );

    private ProviderNames() {
    }

    public static boolean isKnown(String providerName) {
        return ALL.contains(providerName);
    }

    public static Set<String> queryMappableProviderNames() {
        return QUERY_MAPPABLE;
    }
}
