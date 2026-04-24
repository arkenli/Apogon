package com.company.aiops.analysis.rule.error;

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

class DefaultErrorRateRuleAnalyzerTests {

    @Test
    void aggregatesErrorCodeUidAndBusinessSemanticsFromErrorMsg() {
        DefaultErrorRateRuleAnalyzer analyzer = new DefaultErrorRateRuleAnalyzer(
                RuleAnalysisTestFixtures.defaultProperties(),
                new DefaultMetricAnomalyDetector(RuleAnalysisTestFixtures.defaultProperties()),
                new DefaultTraceTopologyAnalyzer()
        );
        List<InterfaceLogEntry> interfaceEntries = List.of(
                new InterfaceLogEntry("ams_front", "vmall-ams.PrizeActivityService.queryPrizeResult", "trace-1", "current-1", null, "uid-1", false, "E100", "user not eligible", 120L, Instant.parse("2026-04-20T10:08:50Z")),
                new InterfaceLogEntry("ams_front", "vmall-ams.PrizeActivityService.queryPrizeResult", "trace-2", "current-2", null, "uid-1", false, "E100", "activity validation failed", 118L, Instant.parse("2026-04-20T10:08:51Z")),
                new InterfaceLogEntry("ams_front", "vmall-ams.PrizeActivityService.queryPrizeResult", "trace-3", "current-3", null, "uid-2", false, "E100", "not eligible for activity", 121L, Instant.parse("2026-04-20T10:08:52Z"))
        );
        RuleAnalysisEvidence evidence = analyzer.analyze(RuleAnalysisTestFixtures.collection(
                RuleAnalysisTestFixtures.errorRateAlert(3),
                BaselineEvidence.available(new InterfaceBaseline(
                        100.0,
                        120.0,
                        150.0,
                        0.01,
                        0.03,
                        0.05,
                        80L,
                        120L,
                        180L,
                        100L,
                        160L,
                        220L,
                        List.of(new InterfaceBaseline.ErrorCodeBaseline("E100", 20L, 0.60, List.of("activity validation failed"))),
                        true
                )),
                InterfaceLogEvidence.available(interfaceEntries, List.of("trace-1", "trace-2", "trace-3")),
                TraceEvidence.available(List.of(
                        new TraceRecord("trace-1", List.of(
                                new InterfaceLogEntry("upstream", "entry", "trace-1", "root", null, "uid-1", true, null, null, 30L, Instant.parse("2026-04-20T10:08:48Z")),
                                new InterfaceLogEntry("ams_front", "vmall-ams.PrizeActivityService.queryPrizeResult", "trace-1", "current", "root", "uid-1", false, "E100", "user not eligible", 120L, Instant.parse("2026-04-20T10:08:50Z"))
                        )),
                        new TraceRecord("trace-2", List.of(
                                new InterfaceLogEntry("upstream", "entry", "trace-2", "root", null, "uid-1", true, null, null, 30L, Instant.parse("2026-04-20T10:08:49Z")),
                                new InterfaceLogEntry("ams_front", "vmall-ams.PrizeActivityService.queryPrizeResult", "trace-2", "current", "root", "uid-1", false, "E100", "activity validation failed", 118L, Instant.parse("2026-04-20T10:08:51Z"))
                        ))
                )),
                RuntimeLogEvidence.available(List.of()),
                MetricEvidence.available(ContainerMetrics.empty(), CseMetrics.empty(), DatabaseMetrics.empty(), DcsMetrics.empty(), MqMetrics.empty(), null)
        ));

        assertThat(evidence.errorCodeDistribution()).singleElement().satisfies(aggregate -> {
            assertThat(aggregate.errorCode()).isEqualTo("E100");
            assertThat(aggregate.share()).isEqualTo(1.0);
            assertThat(aggregate.uidCount()).isEqualTo(2);
            assertThat(aggregate.businessSemantic()).isTrue();
            assertThat(aggregate.evidenceId()).isEqualTo("error.top.1");
        });
        assertThat(evidence.uidDistribution()).first().satisfies(aggregate -> {
            assertThat(aggregate.uid()).isEqualTo("uid-1");
            assertThat(aggregate.count()).isEqualTo(2);
            assertThat(aggregate.evidenceId()).isEqualTo("uid.top.1");
        });
        assertThat(evidence.ruleFindings()).extracting("type")
                .contains("LOW_TRAFFIC_LIMITED_IMPACT", "BUSINESS_ERROR_CODE_NOT_MASKED");
        assertThat(evidence.candidateRootCauses()).first().satisfies(candidate -> {
            assertThat(candidate.causeType()).isEqualTo("BUSINESS_ERROR_CODE_NOT_MASKED");
            assertThat(candidate.evidenceIds()).isNotEmpty();
        });
        assertThat(evidence.evidenceRefs()).extracting("evidenceId").contains("error.top.1", "uid.top.1");
        assertThat(evidence.ruleFindings()).allSatisfy(finding -> assertThat(finding.evidenceIds()).isNotEmpty());
    }

    @Test
    void identifiesRepeatedDownstreamFailureAcrossSampledTraces() {
        DefaultErrorRateRuleAnalyzer analyzer = new DefaultErrorRateRuleAnalyzer(
                RuleAnalysisTestFixtures.defaultProperties(),
                new DefaultMetricAnomalyDetector(RuleAnalysisTestFixtures.defaultProperties()),
                new DefaultTraceTopologyAnalyzer()
        );
        List<InterfaceLogEntry> interfaceEntries = List.of(
                new InterfaceLogEntry("ams_front", "vmall-ams.PrizeActivityService.queryPrizeResult", "trace-a", "current-a", null, "uid-1", false, "E500", "rpc failed", 220L, Instant.parse("2026-04-20T10:08:50Z")),
                new InterfaceLogEntry("ams_front", "vmall-ams.PrizeActivityService.queryPrizeResult", "trace-b", "current-b", null, "uid-2", false, "E500", "rpc failed", 210L, Instant.parse("2026-04-20T10:08:51Z"))
        );
        RuleAnalysisEvidence evidence = analyzer.analyze(RuleAnalysisTestFixtures.collection(
                RuleAnalysisTestFixtures.errorRateAlert(20),
                BaselineEvidence.available(InterfaceBaseline.empty()),
                InterfaceLogEvidence.available(interfaceEntries, List.of("trace-a", "trace-b")),
                TraceEvidence.available(List.of(
                        new TraceRecord("trace-a", List.of(
                                new InterfaceLogEntry("upstream", "entry", "trace-a", "root", null, "uid-1", true, null, null, 30L, Instant.parse("2026-04-20T10:08:48Z")),
                                new InterfaceLogEntry("ams_front", "vmall-ams.PrizeActivityService.queryPrizeResult", "trace-a", "current", "root", "uid-1", false, "E500", "rpc failed", 220L, Instant.parse("2026-04-20T10:08:50Z")),
                                new InterfaceLogEntry("inventory-service", "inventory.reserve", "trace-a", "downstream", "current", "uid-1", false, "E500", "rpc failed", 180L, Instant.parse("2026-04-20T10:08:49Z"))
                        )),
                        new TraceRecord("trace-b", List.of(
                                new InterfaceLogEntry("upstream", "entry", "trace-b", "root", null, "uid-2", true, null, null, 30L, Instant.parse("2026-04-20T10:08:48Z")),
                                new InterfaceLogEntry("ams_front", "vmall-ams.PrizeActivityService.queryPrizeResult", "trace-b", "current", "root", "uid-2", false, "E500", "rpc failed", 210L, Instant.parse("2026-04-20T10:08:51Z")),
                                new InterfaceLogEntry("inventory-service", "inventory.reserve", "trace-b", "downstream", "current", "uid-2", false, "E500", "rpc failed", 170L, Instant.parse("2026-04-20T10:08:49Z"))
                        ))
                )),
                RuntimeLogEvidence.available(List.of()),
                MetricEvidence.available(ContainerMetrics.empty(), CseMetrics.empty(), DatabaseMetrics.empty(), DcsMetrics.empty(), MqMetrics.empty(), null)
        ));

        assertThat(evidence.downstreamObservations()).singleElement().satisfies(observation -> {
            assertThat(observation.serviceName()).isEqualTo("inventory-service");
            assertThat(observation.interfaceName()).isEqualTo("inventory.reserve");
            assertThat(observation.traceCount()).isEqualTo(2);
            assertThat(observation.evidenceId()).startsWith("trace.downstream.failure.");
        });
        assertThat(evidence.candidateRootCauses()).first().satisfies(candidate -> assertThat(candidate.causeType()).isEqualTo("DOWNSTREAM_RPC_FAILURE"));
        assertThat(evidence.ruleFindings()).extracting("type").contains("DOWNSTREAM_RPC_FAILURE");
    }
}
