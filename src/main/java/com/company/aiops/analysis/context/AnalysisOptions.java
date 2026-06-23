package com.company.aiops.analysis.context;

/**
 * Runtime limits that keep provider collection and evidence compression within configured bounds.
 */
public record AnalysisOptions(
        int maxInterfaceLogLimit,
        int maxTraceSampleSize,
        int maxSlowTraceSampleSize,
        int maxRuntimeLogSize,
        int runtimeLogMessageMaxChars,
        int maxErrorCodeSize,
        int maxUidSize,
        int errorLogMessageQueryMaxAttempts,
        int errorLogMessageQueryMinChars,
        int errorLogMessageQueryMinTokens,
        String errorLogQueryLevel,
        boolean llmEnabled,
        boolean providerParallelEnabled
) {
}
