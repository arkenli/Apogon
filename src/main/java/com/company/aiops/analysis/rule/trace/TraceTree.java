package com.company.aiops.analysis.rule.trace;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Flat trace tree view with helper methods for rule analyzers.
 *
 * <p>The model keeps spans as a stable ordered list instead of a recursive object graph so it is
 * easier to serialize, test, and reuse in later evidence-compression stages.</p>
 */
public record TraceTree(
        String traceId,
        List<TraceSpan> spans,
        List<String> rootSpanIds,
        List<String> unresolvedParentSpanIds
) {

    public TraceTree {
        spans = spans == null ? List.of() : List.copyOf(spans);
        rootSpanIds = rootSpanIds == null ? List.of() : List.copyOf(rootSpanIds);
        unresolvedParentSpanIds = unresolvedParentSpanIds == null
                ? List.of()
                : List.copyOf(unresolvedParentSpanIds);
    }

    public Optional<TraceSpan> rootSpan() {
        return rootSpanIds.stream().findFirst().flatMap(this::findSpan);
    }

    public Optional<TraceSpan> findSpan(String spanId) {
        if (spanId == null || spanId.isBlank()) {
            return Optional.empty();
        }
        return spans.stream().filter(span -> spanId.equals(span.spanId())).findFirst();
    }

    public List<TraceSpan> childSpans(String parentSpanId) {
        if (parentSpanId == null || parentSpanId.isBlank()) {
            return List.of();
        }
        return spans.stream()
                .filter(span -> parentSpanId.equals(span.parentSpanId()))
                .toList();
    }

    public Optional<TraceSpan> firstFailedSpan() {
        return spans.stream()
                .filter(span -> !span.success())
                .min(Comparator.comparing(TraceSpan::timestamp, Comparator.nullsLast(Comparator.naturalOrder())));
    }

    public Optional<TraceSpan> slowestSpan() {
        return spans.stream().max(Comparator.comparingLong(TraceSpan::latencyMs));
    }

    public List<TraceSpan> spansForService(String serviceName) {
        return spans.stream()
                .filter(span -> Objects.equals(serviceName, span.serviceName()))
                .toList();
    }
}
