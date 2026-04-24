package com.company.aiops.analysis.evidence;

import com.company.aiops.provider.model.InterfaceBaseline;

/**
 * Baseline evidence always exists as an object so later analyzers can tell whether the provider
 * returned data, returned nothing, or was skipped/failed.
 */
public record BaselineEvidence(
        InterfaceBaseline baseline,
        boolean collected,
        String missingReason
) {

    public BaselineEvidence {
        baseline = baseline == null ? InterfaceBaseline.empty() : baseline;
    }

    public static BaselineEvidence available(InterfaceBaseline baseline) {
        return new BaselineEvidence(baseline, true, null);
    }

    public static BaselineEvidence missing(String reason) {
        return new BaselineEvidence(InterfaceBaseline.empty(), false, reason);
    }
}
