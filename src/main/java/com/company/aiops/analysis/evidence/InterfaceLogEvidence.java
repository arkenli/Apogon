package com.company.aiops.analysis.evidence;

import com.company.aiops.provider.model.InterfaceLogEntry;

import java.util.List;

/**
 * Interface-log evidence plus the sampled trace ids derived from those logs.
 */
public record InterfaceLogEvidence(
        List<InterfaceLogEntry> entries,
        List<String> sampledTraceIds,
        boolean collected,
        String missingReason
) {

    public InterfaceLogEvidence {
        entries = entries == null ? List.of() : List.copyOf(entries);
        sampledTraceIds = sampledTraceIds == null ? List.of() : List.copyOf(sampledTraceIds);
    }

    public static InterfaceLogEvidence available(List<InterfaceLogEntry> entries, List<String> sampledTraceIds) {
        return new InterfaceLogEvidence(entries, sampledTraceIds, true, null);
    }

    public static InterfaceLogEvidence missing(String reason) {
        return new InterfaceLogEvidence(List.of(), List.of(), false, reason);
    }
}
