package com.company.aiops.analysis.evidence;

import java.util.List;

/**
 * Trace evidence collected from sampled trace ids.
 */
public record TraceEvidence(
        List<TraceRecord> traces,
        boolean collected,
        String missingReason
) {

    public TraceEvidence {
        traces = traces == null ? List.of() : List.copyOf(traces);
    }

    public static TraceEvidence available(List<TraceRecord> traces) {
        return new TraceEvidence(traces, true, null);
    }

    public static TraceEvidence missing(String reason) {
        return new TraceEvidence(List.of(), false, reason);
    }
}
