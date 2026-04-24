package com.company.aiops.analysis.rule.trace;

import com.company.aiops.provider.model.InterfaceLogEntry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Default span-topology builder that reconstructs parent/child links from normalized interface
 * logs.
 */
@Component
public class DefaultTraceTopologyAnalyzer implements TraceTopologyAnalyzer {

    @Override
    public TraceTree buildTraceTree(List<InterfaceLogEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return new TraceTree(null, List.of(), List.of(), List.of());
        }

        Map<String, TraceSpan> spansById = new LinkedHashMap<>();
        entries.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator
                        .comparing(InterfaceLogEntry::timestamp, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(InterfaceLogEntry::serviceName, Comparator.nullsLast(String::compareTo))
                        .thenComparing(InterfaceLogEntry::interfaceName, Comparator.nullsLast(String::compareTo))
                        .thenComparing(InterfaceLogEntry::spanId, Comparator.nullsLast(String::compareTo)))
                .forEach(entry -> {
                    if (entry.spanId() == null || entry.spanId().isBlank()) {
                        return;
                    }
                    spansById.putIfAbsent(entry.spanId(), new TraceSpan(
                            entry.spanId(),
                            entry.parentSpanId(),
                            entry.serviceName(),
                            entry.interfaceName(),
                            entry.success(),
                            entry.errorCode(),
                            entry.errorMsg(),
                            entry.latencyMs(),
                            entry.timestamp()
                    ));
                });

        List<String> rootSpanIds = new ArrayList<>();
        List<String> unresolvedParentSpanIds = new ArrayList<>();
        for (TraceSpan span : spansById.values()) {
            if (span.parentSpanId() == null || span.parentSpanId().isBlank()) {
                rootSpanIds.add(span.spanId());
                continue;
            }
            if (!spansById.containsKey(span.parentSpanId())) {
                rootSpanIds.add(span.spanId());
                unresolvedParentSpanIds.add(span.parentSpanId());
            }
        }

        String traceId = entries.stream()
                .map(InterfaceLogEntry::traceId)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
        return new TraceTree(
                traceId,
                List.copyOf(spansById.values()),
                rootSpanIds.stream().distinct().toList(),
                unresolvedParentSpanIds.stream().distinct().toList()
        );
    }
}
