package com.company.aiops.analysis.evidence;

import com.company.aiops.provider.model.RuntimeLogEntry;

import java.util.List;

/**
 * Runtime log evidence collected after trace sampling.
 */
public record RuntimeLogEvidence(
        List<RuntimeLogEntry> entries,
        boolean collected,
        String missingReason
) {

    public RuntimeLogEvidence {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public static RuntimeLogEvidence available(List<RuntimeLogEntry> entries) {
        return new RuntimeLogEvidence(entries, true, null);
    }

    public static RuntimeLogEvidence missing(String reason) {
        return new RuntimeLogEvidence(List.of(), false, reason);
    }
}
