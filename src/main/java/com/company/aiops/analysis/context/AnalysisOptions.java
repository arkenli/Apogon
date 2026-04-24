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
        boolean llmEnabled,
        boolean providerParallelEnabled
) {
}
