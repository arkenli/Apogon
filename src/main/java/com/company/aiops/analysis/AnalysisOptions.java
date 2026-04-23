package com.company.aiops.analysis;

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
