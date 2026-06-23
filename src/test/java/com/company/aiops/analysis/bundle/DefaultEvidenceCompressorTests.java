package com.company.aiops.analysis.bundle;

import com.company.aiops.alert.model.AlertEvent;
import com.company.aiops.alert.model.AlertTriggerValues;
import com.company.aiops.analysis.context.AnalysisContext;
import com.company.aiops.analysis.context.AnalysisOptions;
import com.company.aiops.analysis.context.TimeWindow;
import com.company.aiops.analysis.evidence.BaselineEvidence;
import com.company.aiops.analysis.evidence.InterfaceLogEvidence;
import com.company.aiops.analysis.evidence.MetricEvidence;
import com.company.aiops.analysis.evidence.ProviderCollectionResult;
import com.company.aiops.analysis.evidence.RuntimeLogEvidence;
import com.company.aiops.analysis.evidence.TraceEvidence;
import com.company.aiops.analysis.result.EvidenceReference;
import com.company.aiops.analysis.result.MissingEvidence;
import com.company.aiops.analysis.rule.model.CandidateRootCause;
import com.company.aiops.analysis.rule.model.DownstreamObservation;
import com.company.aiops.analysis.rule.model.ErrorCodeAggregate;
import com.company.aiops.analysis.rule.model.MetricAnomaly;
import com.company.aiops.analysis.rule.model.RuleAnalysisEvidence;
import com.company.aiops.analysis.rule.model.RuleFinding;
import com.company.aiops.analysis.rule.model.TraceObservation;
import com.company.aiops.analysis.rule.model.UidAggregate;
import com.company.aiops.analysis.rule.RuleAnalysisTestFixtures;
import com.company.aiops.provider.model.ContainerMetrics;
import com.company.aiops.provider.model.CseMetrics;
import com.company.aiops.provider.model.DatabaseMetrics;
import com.company.aiops.provider.model.DcsMetrics;
import com.company.aiops.provider.model.InterfaceBaseline;
import com.company.aiops.provider.model.InterfaceLogEntry;
import com.company.aiops.provider.model.MqMetrics;
import com.company.aiops.provider.model.ResourceMapping;
import com.company.aiops.provider.model.RuntimeLogEntry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultEvidenceCompressorTests {

    private final DefaultEvidenceCompressor compressor = new DefaultEvidenceCompressor();

    @Test
    void compressAppliesConfiguredLimitsAndPreservesReferencedEvidence() {
        AlertEvent alert = new AlertEvent(
                "alert-error",
                com.company.aiops.alert.model.AlertCategory.RPC_ERROR_RATE,
                "OPEN",
                "MAJOR",
                "demo-service",
                "demo.Interface.query",
                "cluster1",
                "site",
                "tenant",
                "application",
                "error alert",
                "metric",
                "AIOps@rate",
                "raw",
                Instant.parse("2026-04-21T02:33:00Z"),
                Instant.parse("2026-04-21T02:33:00Z"),
                new AlertTriggerValues(12.0, 0.75, null, null, null, null, null, null, null, "demo", "raw")
        );
        ProviderCollectionResult collection = collection(
                alert,
                new AnalysisOptions(2000, 2, 1, 2, 10, 2, 1, 5, 8, 2, "ERROR", false, true),
                List.of(
                        new InterfaceLogEntry("demo-service", "demo.Interface.query", "trace-1", "1", null, "uid-alpha", false, "E1", "first", 120L, Instant.parse("2026-04-21T02:31:00Z")),
                        new InterfaceLogEntry("demo-service", "demo.Interface.query", "trace-2", "2", null, "uid-bravo", false, "E2", "second", 240L, Instant.parse("2026-04-21T02:31:10Z")),
                        new InterfaceLogEntry("demo-service", "demo.Interface.query", "trace-3", "3", null, "uid-charlie", true, null, null, 360L, Instant.parse("2026-04-21T02:31:20Z"))
                ),
                List.of(
                        runtimeLogEntry("demo-service", "trace-3", "WARN", "1234567890-ABCDEFGHIJ", Instant.parse("2026-04-21T02:31:22Z")),
                        runtimeLogEntry("demo-service", "trace-2", "ERROR", "ABCDEFGHIJ-1234567890", Instant.parse("2026-04-21T02:31:21Z")),
                        runtimeLogEntry("demo-service", "trace-1", "WARN", "short", Instant.parse("2026-04-21T02:31:20Z"))
                ),
                List.of(new MissingEvidence("runtimeLog", "timeout", false, "runtime details weakened"))
        );

        RuleAnalysisEvidence ruleAnalysis = new RuleAnalysisEvidence(
                List.of(
                        new ErrorCodeAggregate("error.top.1", "E1", 5, 0.5, 3, 3, false, false, List.of("first")),
                        new ErrorCodeAggregate("error.top.2", "E2", 3, 0.3, 2, 2, false, false, List.of("second")),
                        new ErrorCodeAggregate("error.top.3", "E3", 2, 0.2, 1, 1, false, true, List.of("third"))
                ),
                List.of(
                        new UidAggregate("uid.top.1", "uid-alpha", 5, 0.5),
                        new UidAggregate("uid.top.2", "uid-bravo", 4, 0.4)
                ),
                List.of(
                        new TraceObservation("trace.failure.1", "trace-1", "FIRST_FAILED_SPAN", "s1", "demo-service", "demo.Interface.query", "E1", false, 120L, "first"),
                        new TraceObservation("trace.failure.2", "trace-2", "FIRST_FAILED_SPAN", "s2", "downstream-a", "rpcA", "E2", false, 240L, "second"),
                        new TraceObservation("trace.failure.3", "trace-3", "FIRST_FAILED_SPAN", "s3", "downstream-b", "rpcB", "E3", false, 360L, "third")
                ),
                List.of(new DownstreamObservation("trace.downstream.failure.1", "downstream-a", "rpcA", 2, 0.66, true, 220L, "dominant")),
                List.of(new MetricAnomaly("metric.container.cpu.1", "CONTAINER", "demo-service", "cpuUsage", 0.91, 0.80, "HIGH", "cpu high")),
                List.of(new RuleFinding(
                        "BUSINESS_ERROR_CODE_NOT_MASKED",
                        0.80,
                        "demo",
                        List.of("error.top.3", "trace.failure.3")
                )),
                List.of(new CandidateRootCause(
                        "BUSINESS_ERROR_CODE_NOT_MASKED",
                        80,
                        0.80,
                        List.of("error.top.3", "uid.top.2", "trace.failure.3"),
                        "dominant business error"
                )),
                List.of(
                        new EvidenceReference("error.top.1", "ERROR_CODE", "first"),
                        new EvidenceReference("error.top.2", "ERROR_CODE", "second"),
                        new EvidenceReference("error.top.3", "ERROR_CODE", "third"),
                        new EvidenceReference("uid.top.1", "UID", "alpha"),
                        new EvidenceReference("uid.top.2", "UID", "bravo"),
                        new EvidenceReference("trace.failure.1", "TRACE", "first trace"),
                        new EvidenceReference("trace.failure.2", "TRACE", "second trace"),
                        new EvidenceReference("trace.failure.3", "TRACE", "third trace"),
                        new EvidenceReference("trace.downstream.failure.1", "DOWNSTREAM", "downstream"),
                        new EvidenceReference("metric.container.cpu.1", "METRIC", "cpu")
                ),
                List.of("rule evidence note")
        );

        CompressedEvidenceBundle bundle = compressor.compress(collection, ruleAnalysis);

        assertThat(bundle.interfaceLogs().topErrorCodes())
                .extracting(ErrorCodeAggregate::evidenceId)
                .containsExactly("error.top.3", "error.top.1");
        assertThat(bundle.interfaceLogs().topUids())
                .extracting(UidAggregate::uid)
                .containsExactly("uid-bravo");
        assertThat(bundle.traces().observations())
                .extracting(TraceObservation::evidenceId)
                .contains("trace.failure.3");
        assertThat(bundle.traces().observations()).hasSize(2);
        assertThat(bundle.runtimeLogs().samples()).hasSize(2);
        assertThat(bundle.runtimeLogs().samples())
                .extracting(CompressedEvidenceBundle.RuntimeLogSample::message)
                .allSatisfy(message -> assertThat(message).hasSizeLessThanOrEqualTo(10));
        assertThat(bundle.ruleAnalysis().candidateRootCauses().get(0).evidenceIds())
                .containsExactly("error.top.3", "uid.top.2", "trace.failure.3");
        assertThat(bundle.evidenceRefs())
                .extracting(EvidenceReference::evidenceId)
                .contains("runtime.log.1", "error.top.3", "uid.top.2", "trace.failure.3");
        assertThat(bundle.missingEvidence())
                .extracting(MissingEvidence::providerName)
                .containsExactly("runtimeLog");
        assertThat(bundle.insufficientEvidence())
                .anySatisfy(note -> assertThat(note).contains("errorCode distribution"))
                .anySatisfy(note -> assertThat(note).contains("uid distribution"))
                .anySatisfy(note -> assertThat(note).contains("trace observations"))
                .anySatisfy(note -> assertThat(note).contains("runtime log samples"))
                .anySatisfy(note -> assertThat(note).contains("runtime log messages"));
    }

    @Test
    void compressUsesSlowTraceLimitAndBackfillsMissingRuleReferenceDescriptions() {
        AlertEvent alert = new AlertEvent(
                "alert-latency",
                com.company.aiops.alert.model.AlertCategory.RPC_LATENCY,
                "OPEN",
                "MAJOR",
                "demo-service",
                "demo.Interface.slow",
                "cluster2",
                "site",
                "tenant",
                "application",
                "latency alert",
                "metric",
                "AIOps@delay",
                "raw",
                Instant.parse("2026-04-22T10:00:00Z"),
                Instant.parse("2026-04-22T10:00:00Z"),
                new AlertTriggerValues(20.0, null, 3200L, 6400L, null, null, null, null, null, "demo", "raw")
        );
        ProviderCollectionResult collection = collection(
                alert,
                new AnalysisOptions(2000, 10, 1, 3, 50, 5, 5, 5, 8, 2, "ERROR", false, true),
                List.of(
                        new InterfaceLogEntry("demo-service", "demo.Interface.slow", "trace-a", "1", null, "uid-a", true, null, null, 1800L, Instant.parse("2026-04-22T09:59:00Z")),
                        new InterfaceLogEntry("demo-service", "demo.Interface.slow", "trace-b", "2", null, "uid-b", true, null, null, 3600L, Instant.parse("2026-04-22T09:59:10Z"))
                ),
                List.of(),
                List.of()
        );

        RuleAnalysisEvidence ruleAnalysis = new RuleAnalysisEvidence(
                List.of(),
                List.of(),
                List.of(
                        new TraceObservation("trace.slow.1", "trace-a", "SLOWEST_SPAN", "s1", "downstream-a", "rpcA", null, true, 900L, "a"),
                        new TraceObservation("trace.slow.2", "trace-b", "SLOWEST_SPAN", "s2", "downstream-b", "rpcB", null, true, 1200L, "b"),
                        new TraceObservation("trace.slow.3", "trace-c", "SLOWEST_SPAN", "s3", "downstream-c", "rpcC", null, true, 2400L, "c")
                ),
                List.of(new DownstreamObservation("trace.slow.aggregation.1", "downstream-c", "rpcC", 2, 0.66, false, 1800L, "dominant")),
                List.of(),
                List.of(new RuleFinding("DOWNSTREAM_RPC_LATENCY", 0.76, "demo", List.of("trace.slow.3"))),
                List.of(new CandidateRootCause("DOWNSTREAM_RPC_LATENCY", 76, 0.76, List.of("trace.slow.3"), "dominant downstream")),
                List.of(new EvidenceReference("trace.slow.aggregation.1", "DOWNSTREAM", "aggregation only")),
                List.of()
        );

        CompressedEvidenceBundle bundle = compressor.compress(collection, ruleAnalysis);

        assertThat(bundle.traces().observations())
                .extracting(TraceObservation::evidenceId)
                .containsExactly("trace.slow.3");
        assertThat(bundle.evidenceRefs())
                .filteredOn(reference -> reference.evidenceId().equals("trace.slow.3"))
                .singleElement()
                .satisfies(reference -> assertThat(reference.evidenceType()).isEqualTo("RULE_REFERENCE"));
    }

    @Test
    void compressPreservesServiceErrorLogQueryAttemptsAndAlertValues() {
        AlertEvent alert = RuleAnalysisTestFixtures.serviceErrorLogAlert();
        ProviderCollectionResult collection = RuleAnalysisTestFixtures.collection(
                alert,
                BaselineEvidence.available(InterfaceBaseline.empty()),
                InterfaceLogEvidence.available(List.of(), List.of()),
                TraceEvidence.available(List.of()),
                RuntimeLogEvidence.availableMessageQuery(
                        List.of(runtimeLogEntry(
                                "ams_front",
                                "trace-log-1",
                                "ERROR",
                                "DEFAULT prebook loginAppointment failed",
                                Instant.parse("2026-04-22T03:14:30Z")
                        )),
                        "loginAppointment failed",
                        List.of(
                                new RuntimeLogEvidence.QueryAttempt(
                                        "runtime.query.attempt.1",
                                        "prebook loginAppointment failed",
                                        "ERROR",
                                        0,
                                        false
                                ),
                                new RuntimeLogEvidence.QueryAttempt(
                                        "runtime.query.attempt.2",
                                        "loginAppointment failed",
                                        "ERROR",
                                        1,
                                        true
                                )
                        ),
                        List.of("trace-log-1")
                ),
                MetricEvidence.available(
                        ContainerMetrics.empty(),
                        CseMetrics.empty(),
                        DatabaseMetrics.empty(),
                        DcsMetrics.empty(),
                        MqMetrics.empty(),
                        null
                )
        );
        RuleAnalysisEvidence ruleAnalysis = new RuleAnalysisEvidence(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new CandidateRootCause(
                        "CURRENT_SERVICE_RUNTIME_EXCEPTION",
                        90,
                        0.90,
                        List.of("runtime.log.1", "runtime.query.attempt.2"),
                        "current service runtime exception"
                )),
                List.of(new EvidenceReference("runtime.log.1", "RUNTIME_LOG", "matched runtime log")),
                List.of()
        );

        CompressedEvidenceBundle bundle = compressor.compress(collection, ruleAnalysis);

        assertThat(bundle.alert().failureCount()).isEqualTo(2.0);
        assertThat(bundle.alert().logLevel()).isEqualTo("ERROR");
        assertThat(bundle.alert().logMessage()).isEqualTo("DEFAULT prebook loginAppointment failed");
        assertThat(bundle.alert().normalizedLogQuery()).isEqualTo("prebook loginAppointment failed");
        assertThat(bundle.runtimeLogs().messageQueryMatched()).isTrue();
        assertThat(bundle.runtimeLogs().matchedLogQuery()).isEqualTo("loginAppointment failed");
        assertThat(bundle.runtimeLogs().queryAttempts())
                .extracting(CompressedEvidenceBundle.RuntimeLogQueryAttempt::evidenceId)
                .containsExactly("runtime.query.attempt.1", "runtime.query.attempt.2");
        assertThat(bundle.runtimeLogs().sampledTraceIds()).containsExactly("trace-log-1");
        assertThat(bundle.evidenceRefs())
                .extracting(EvidenceReference::evidenceId)
                .contains(
                        "runtime.query.attempt.1",
                        "runtime.query.attempt.2",
                        "runtime.log.1",
                        "alert.failure.count",
                        "alert.log.level",
                        "alert.log.message",
                        "alert.log.normalized_query"
                );
    }

    @Test
    void compressSkipsNullRuntimeLogSamplesAndQueryAttempts() {
        AlertEvent alert = RuleAnalysisTestFixtures.serviceErrorLogAlert();
        List<RuntimeLogEntry> runtimeLogs = new java.util.ArrayList<>();
        runtimeLogs.add(null);
        runtimeLogs.add(runtimeLogEntry(
                "ams_front",
                "trace-log-1",
                "ERROR",
                "DEFAULT prebook loginAppointment failed",
                Instant.parse("2026-04-22T03:14:30Z")
        ));
        List<RuntimeLogEvidence.QueryAttempt> queryAttempts = new java.util.ArrayList<>();
        queryAttempts.add(null);
        queryAttempts.add(new RuntimeLogEvidence.QueryAttempt(
                "runtime.query.attempt.1",
                "loginAppointment failed",
                "ERROR",
                1,
                true
        ));

        ProviderCollectionResult collection = RuleAnalysisTestFixtures.collection(
                alert,
                BaselineEvidence.available(InterfaceBaseline.empty()),
                InterfaceLogEvidence.available(List.of(), List.of()),
                TraceEvidence.available(List.of()),
                RuntimeLogEvidence.availableMessageQuery(
                        runtimeLogs,
                        "loginAppointment failed",
                        queryAttempts,
                        List.of("trace-log-1")
                ),
                MetricEvidence.available(
                        ContainerMetrics.empty(),
                        CseMetrics.empty(),
                        DatabaseMetrics.empty(),
                        DcsMetrics.empty(),
                        MqMetrics.empty(),
                        null
                )
        );

        CompressedEvidenceBundle bundle = compressor.compress(collection, RuleAnalysisEvidence.empty());

        assertThat(bundle.runtimeLogs().samples())
                .extracting(CompressedEvidenceBundle.RuntimeLogSample::evidenceId)
                .containsExactly("runtime.log.1");
        assertThat(bundle.runtimeLogs().queryAttempts())
                .extracting(CompressedEvidenceBundle.RuntimeLogQueryAttempt::evidenceId)
                .containsExactly("runtime.query.attempt.1");
    }

    private ProviderCollectionResult collection(
            AlertEvent alert,
            AnalysisOptions options,
            List<InterfaceLogEntry> interfaceEntries,
            List<RuntimeLogEntry> runtimeLogs,
            List<MissingEvidence> missingEvidence
    ) {
        Instant occurTime = alert.metricOccurTime();
        AnalysisContext context = new AnalysisContext(
                alert,
                new TimeWindow(occurTime.minus(Duration.ofMinutes(10)), occurTime.plus(Duration.ofMinutes(2))),
                new TimeWindow(occurTime.minus(Duration.ofDays(1)).minus(Duration.ofMinutes(5)), occurTime.minus(Duration.ofDays(1)).plus(Duration.ofMinutes(5))),
                ResourceMapping.empty(alert.serviceName(), alert.cluster()),
                options
        );
        return new ProviderCollectionResult(
                context,
                BaselineEvidence.available(new InterfaceBaseline(10.0, 0.10, 100L, 200L, 260L, List.of(), false)),
                InterfaceLogEvidence.available(interfaceEntries, interfaceEntries.stream().map(InterfaceLogEntry::traceId).toList()),
                TraceEvidence.available(List.of()),
                RuntimeLogEvidence.available(runtimeLogs),
                MetricEvidence.available(
                        ContainerMetrics.empty(),
                        CseMetrics.empty(),
                        DatabaseMetrics.empty(),
                        DcsMetrics.empty(),
                        MqMetrics.empty(),
                        null
                ),
                missingEvidence,
                !missingEvidence.isEmpty(),
                false,
                null,
                List.of()
        );
    }

    private RuntimeLogEntry runtimeLogEntry(
            String serviceName,
            String traceId,
            String level,
            String message,
            Instant timestamp
    ) {
        return new RuntimeLogEntry(
                serviceName,
                null,
                null,
                traceId,
                null,
                level,
                null,
                null,
                message,
                null,
                null,
                timestamp
        );
    }
}
