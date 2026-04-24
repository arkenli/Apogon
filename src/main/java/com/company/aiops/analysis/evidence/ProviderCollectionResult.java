package com.company.aiops.analysis.evidence;

import com.company.aiops.analysis.context.AnalysisContext;
import com.company.aiops.analysis.result.MissingEvidence;
import com.company.aiops.provider.model.ProviderResult;

import java.util.List;
import java.util.Objects;

/**
 * Result of the provider collection stage. This becomes the handoff point to later rule analysis
 * and also carries enough degradation metadata for phase-4 placeholder results.
 */
public record ProviderCollectionResult(
        AnalysisContext context,
        BaselineEvidence baselineEvidence,
        InterfaceLogEvidence interfaceLogEvidence,
        TraceEvidence traceEvidence,
        RuntimeLogEvidence runtimeLogEvidence,
        MetricEvidence metricEvidence,
        List<MissingEvidence> missingEvidence,
        boolean degradedByProviderFailure,
        boolean ruleFallbackRequired,
        String degradedReason,
        List<ProviderResult<?>> providerResults
) {

    public ProviderCollectionResult {
        context = Objects.requireNonNull(context, "context");
        baselineEvidence = Objects.requireNonNull(baselineEvidence, "baselineEvidence");
        interfaceLogEvidence = Objects.requireNonNull(interfaceLogEvidence, "interfaceLogEvidence");
        traceEvidence = Objects.requireNonNull(traceEvidence, "traceEvidence");
        runtimeLogEvidence = Objects.requireNonNull(runtimeLogEvidence, "runtimeLogEvidence");
        metricEvidence = Objects.requireNonNull(metricEvidence, "metricEvidence");
        missingEvidence = missingEvidence == null ? List.of() : List.copyOf(missingEvidence);
        providerResults = providerResults == null ? List.of() : List.copyOf(providerResults);
    }
}
