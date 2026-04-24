package com.company.aiops.provider.orchestrator;

import com.company.aiops.analysis.context.AnalysisContext;
import com.company.aiops.analysis.evidence.ProviderCollectionResult;

/**
 * Coordinates provider calls for one analysis task.
 */
public interface ProviderOrchestrator {

    ProviderCollectionResult collect(String taskId, AnalysisContext context);
}
