package com.company.aiops.llm.schema;

import java.util.Set;

/**
 * Enumerated constraints mirrored from design section 12.7 so validation stays centralized.
 */
public final class LlmOutputSchema {

    public static final Set<String> IMPACT_LEVELS = Set.of("LOW", "MEDIUM", "HIGH", "UNKNOWN");
    public static final Set<String> RECOMMENDATION_PRIORITIES = Set.of("P0", "P1", "P2", "P3");
    public static final Set<String> RECOMMENDATION_ACTION_TYPES = Set.of(
            "CHECK_TRACE",
            "CHECK_LOG",
            "CHECK_METRIC",
            "CHECK_DOWNSTREAM",
            "CHECK_DATABASE",
            "CHECK_DCS",
            "CHECK_MQ",
            "EVALUATE_ERROR_CODE_MASKING",
            "TUNE_ALERT_RULE",
            "MANUAL_CONFIRMATION"
    );
    public static final Set<String> ROOT_CAUSE_TYPES = Set.of(
            "BUSINESS_ERROR_CODE_NOT_MASKED",
            "LOW_TRAFFIC_LIMITED_IMPACT",
            "DOWNSTREAM_RPC_FAILURE",
            "DOWNSTREAM_RPC_LATENCY",
            "DATABASE_SLOW_QUERY",
            "DATABASE_RESOURCE_PRESSURE",
            "DCS_LATENCY_OR_FAILURE",
            "MQ_BACKLOG_OR_LATENCY",
            "CONTAINER_CPU_PRESSURE",
            "CONTAINER_MEMORY_PRESSURE",
            "GC_PRESSURE",
            "INSTANCE_STARTING_OR_FAILED",
            "SINGLE_INSTANCE_ABNORMAL",
            "TRAFFIC_SPIKE",
            "UNKNOWN"
    );

    private LlmOutputSchema() {
    }
}
