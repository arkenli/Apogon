package com.company.aiops.analysis.rule.trace;

import com.company.aiops.provider.model.InterfaceLogEntry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultTraceTopologyAnalyzerTests {

    @Test
    void buildsParentChildRelationshipsFromSpanIds() {
        DefaultTraceTopologyAnalyzer analyzer = new DefaultTraceTopologyAnalyzer();
        List<InterfaceLogEntry> entries = List.of(
                new InterfaceLogEntry("svc-a", "root.if", "trace-1", "root", null, "uid", true, null, null, 120L, Instant.parse("2026-04-21T02:33:00Z")),
                new InterfaceLogEntry("svc-b", "mid.if", "trace-1", "mid", "root", "uid", true, null, null, 80L, Instant.parse("2026-04-21T02:33:01Z")),
                new InterfaceLogEntry("svc-c", "leaf.if", "trace-1", "leaf", "mid", "uid", false, "E100", "failure", 40L, Instant.parse("2026-04-21T02:33:02Z")),
                new InterfaceLogEntry("svc-d", "orphan.if", "trace-1", "orphan", "missing", "uid", true, null, null, 20L, Instant.parse("2026-04-21T02:33:03Z"))
        );

        TraceTree tree = analyzer.buildTraceTree(entries);

        assertThat(tree.traceId()).isEqualTo("trace-1");
        assertThat(tree.rootSpanIds()).containsExactly("root", "orphan");
        assertThat(tree.childSpans("root")).extracting(TraceSpan::spanId).containsExactly("mid");
        assertThat(tree.childSpans("mid")).extracting(TraceSpan::spanId).containsExactly("leaf");
        assertThat(tree.unresolvedParentSpanIds()).containsExactly("missing");
        assertThat(tree.firstFailedSpan()).hasValueSatisfying(span -> assertThat(span.spanId()).isEqualTo("leaf"));
        assertThat(tree.slowestSpan()).hasValueSatisfying(span -> assertThat(span.spanId()).isEqualTo("root"));
    }
}
