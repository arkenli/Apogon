package com.company.aiops.analysis.evidence;

import com.company.aiops.provider.model.InterfaceLogEntry;

import java.util.List;

/**
 * One sampled trace plus its normalized span list.
 */
public record TraceRecord(String traceId, List<InterfaceLogEntry> entries) {

    public TraceRecord {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
