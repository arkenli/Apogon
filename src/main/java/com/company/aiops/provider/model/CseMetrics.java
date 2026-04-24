package com.company.aiops.provider.model;

/**
 * RPC/CSE framework pressure indicators for the alerting service.
 */
public record CseMetrics(
        double threadPoolUsage,
        double connectionUsage
) {

    public static CseMetrics empty() {
        return new CseMetrics(0.0, 0.0);
    }
}
