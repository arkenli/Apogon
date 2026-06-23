package com.company.aiops.analysis.evidence;

import com.company.aiops.provider.model.RuntimeLogEntry;

import java.util.List;
import java.util.Objects;

/**
 * Runtime log evidence collected after trace sampling.
 */
public record RuntimeLogEvidence(
        List<RuntimeLogEntry> entries,
        boolean collected,
        String missingReason,
        boolean messageQueryMatched,
        String matchedLogQuery,
        List<QueryAttempt> queryAttempts,
        List<String> sampledTraceIds
) {

    public RuntimeLogEvidence {
        entries = entries == null ? List.of() : entries.stream().filter(Objects::nonNull).toList();
        queryAttempts = queryAttempts == null ? List.of() : queryAttempts.stream().filter(Objects::nonNull).toList();
        sampledTraceIds = sampledTraceIds == null ? List.of() : sampledTraceIds.stream().filter(Objects::nonNull).toList();
    }

    public static RuntimeLogEvidence available(List<RuntimeLogEntry> entries) {
        return new RuntimeLogEvidence(entries, true, null, false, null, List.of(), List.of());
    }

    public static RuntimeLogEvidence availableMessageQuery(
            List<RuntimeLogEntry> entries,
            String matchedLogQuery,
            List<QueryAttempt> queryAttempts,
            List<String> sampledTraceIds
    ) {
        return new RuntimeLogEvidence(entries, true, null, true, matchedLogQuery, queryAttempts, sampledTraceIds);
    }

    public static RuntimeLogEvidence missing(String reason) {
        return new RuntimeLogEvidence(List.of(), false, reason, false, null, List.of(), List.of());
    }

    public record QueryAttempt(String evidenceId, String query, String level, int hitCount, boolean selected) {
    }
}
