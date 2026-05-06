package com.company.aiops.analysis.rule.latency;

import com.company.aiops.analysis.evidence.BaselineEvidence;
import com.company.aiops.analysis.evidence.InterfaceLogEvidence;
import com.company.aiops.analysis.evidence.MetricEvidence;
import com.company.aiops.analysis.evidence.RuntimeLogEvidence;
import com.company.aiops.analysis.evidence.TraceEvidence;
import com.company.aiops.analysis.evidence.TraceRecord;
import com.company.aiops.analysis.rule.RuleAnalysisTestFixtures;
import com.company.aiops.analysis.rule.metric.DefaultMetricAnomalyDetector;
import com.company.aiops.analysis.rule.model.RuleAnalysisEvidence;
import com.company.aiops.analysis.rule.trace.DefaultTraceTopologyAnalyzer;
import com.company.aiops.provider.model.ContainerMetrics;
import com.company.aiops.provider.model.CseMetrics;
import com.company.aiops.provider.model.DatabaseMetrics;
import com.company.aiops.provider.model.DcsMetrics;
import com.company.aiops.provider.model.InterfaceBaseline;
import com.company.aiops.provider.model.InterfaceLogEntry;
import com.company.aiops.provider.model.MqMetrics;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultLatencyRuleAnalyzerTests {

    @Test
    void findsSlowTracesAndDominantSlowestDownstreamSpan() {
        DefaultLatencyRuleAnalyzer analyzer = new DefaultLatencyRuleAnalyzer(
                RuleAnalysisTestFixtures.defaultProperties(),
                new DefaultMetricAnomalyDetector(RuleAnalysisTestFixtures.defaultProperties()),
                new DefaultTraceTopologyAnalyzer()
        );
        List<InterfaceLogEntry> interfaceEntries = List.of(
                new InterfaceLogEntry("vmall_ingresscache_ica", "persistent://WiseCloudDMQService/WiseCloudDMQService/MQTopic_PMS.refreshAppProduct_cluster2", "trace-1", "current-1", null, "uid-1", true, null, null, 3200L, Instant.parse("2026-04-21T02:32:50Z")),
                new InterfaceLogEntry("vmall_ingresscache_ica", "persistent://WiseCloudDMQService/WiseCloudDMQService/MQTopic_PMS.refreshAppProduct_cluster2", "trace-2", "current-2", null, "uid-2", true, null, null, 3000L, Instant.parse("2026-04-21T02:32:51Z")),
                new InterfaceLogEntry("vmall_ingresscache_ica", "persistent://WiseCloudDMQService/WiseCloudDMQService/MQTopic_PMS.refreshAppProduct_cluster2", "trace-3", "current-3", null, "uid-3", true, null, null, 2900L, Instant.parse("2026-04-21T02:32:52Z"))
        );
        RuleAnalysisEvidence evidence = analyzer.analyze(RuleAnalysisTestFixtures.collection(
                RuleAnalysisTestFixtures.latencyAlert(49, 3067L),
                BaselineEvidence.available(new InterfaceBaseline(
                        60.0,
                        0.01,
                        220L,
                        480L,
                        1800L,
                        List.of(),
                        false
                )),
                InterfaceLogEvidence.available(interfaceEntries, List.of("trace-1", "trace-2", "trace-3")),
                TraceEvidence.available(List.of(
                        new TraceRecord("trace-1", List.of(
                                new InterfaceLogEntry("upstream", "entry", "trace-1", "root", null, "uid-1", true, null, null, 50L, Instant.parse("2026-04-21T02:32:49Z")),
                                new InterfaceLogEntry("vmall_ingresscache_ica", "persistent://WiseCloudDMQService/WiseCloudDMQService/MQTopic_PMS.refreshAppProduct_cluster2", "trace-1", "current", "root", "uid-1", true, null, null, 900L, Instant.parse("2026-04-21T02:32:50Z")),
                                new InterfaceLogEntry("slow-downstream", "cache.read", "trace-1", "leaf", "current", "uid-1", true, null, null, 2100L, Instant.parse("2026-04-21T02:32:51Z"))
                        )),
                        new TraceRecord("trace-2", List.of(
                                new InterfaceLogEntry("upstream", "entry", "trace-2", "root", null, "uid-2", true, null, null, 50L, Instant.parse("2026-04-21T02:32:49Z")),
                                new InterfaceLogEntry("vmall_ingresscache_ica", "persistent://WiseCloudDMQService/WiseCloudDMQService/MQTopic_PMS.refreshAppProduct_cluster2", "trace-2", "current", "root", "uid-2", true, null, null, 850L, Instant.parse("2026-04-21T02:32:50Z")),
                                new InterfaceLogEntry("slow-downstream", "cache.read", "trace-2", "leaf", "current", "uid-2", true, null, null, 2200L, Instant.parse("2026-04-21T02:32:51Z"))
                        )),
                        new TraceRecord("trace-3", List.of(
                                new InterfaceLogEntry("upstream", "entry", "trace-3", "root", null, "uid-3", true, null, null, 50L, Instant.parse("2026-04-21T02:32:49Z")),
                                new InterfaceLogEntry("vmall_ingresscache_ica", "persistent://WiseCloudDMQService/WiseCloudDMQService/MQTopic_PMS.refreshAppProduct_cluster2", "trace-3", "current", "root", "uid-3", true, null, null, 880L, Instant.parse("2026-04-21T02:32:50Z")),
                                new InterfaceLogEntry("slow-downstream", "cache.read", "trace-3", "leaf", "current", "uid-3", true, null, null, 2000L, Instant.parse("2026-04-21T02:32:51Z"))
                        ))
                )),
                RuntimeLogEvidence.available(List.of()),
                MetricEvidence.available(ContainerMetrics.empty(), CseMetrics.empty(), DatabaseMetrics.empty(), DcsMetrics.empty(), MqMetrics.empty(), null)
        ));

        assertThat(evidence.traceObservations()).hasSize(3);
        assertThat(evidence.traceObservations()).allSatisfy(observation -> {
            assertThat(observation.observationType()).isEqualTo("SLOWEST_SPAN");
            assertThat(observation.evidenceId()).startsWith("trace.slow.");
        });
        assertThat(evidence.downstreamObservations()).singleElement().satisfies(observation -> {
            assertThat(observation.serviceName()).isEqualTo("slow-downstream");
            assertThat(observation.interfaceName()).isEqualTo("cache.read");
            assertThat(observation.traceCount()).isEqualTo(3);
            assertThat(observation.share()).isEqualTo(1.0);
        });
        assertThat(evidence.ruleFindings()).extracting("type")
                .contains("BROAD_LATENCY_DEGRADATION", "DOWNSTREAM_RPC_LATENCY");
        assertThat(evidence.candidateRootCauses()).first().satisfies(candidate -> assertThat(candidate.causeType()).isEqualTo("DOWNSTREAM_RPC_LATENCY"));
        assertThat(evidence.evidenceRefs()).extracting("evidenceId")
                .contains("latency.profile.1", "trace.slow.1", "trace.slow.aggregation.1");
    }
}
