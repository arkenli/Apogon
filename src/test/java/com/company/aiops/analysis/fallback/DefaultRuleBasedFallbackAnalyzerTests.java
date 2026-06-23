package com.company.aiops.analysis.fallback;

import com.company.aiops.alert.model.AlertEvent;
import com.company.aiops.analysis.bundle.CompressedEvidenceBundle;
import com.company.aiops.analysis.context.AnalysisContext;
import com.company.aiops.analysis.context.AnalysisOptions;
import com.company.aiops.analysis.context.TimeWindow;
import com.company.aiops.analysis.evidence.BaselineEvidence;
import com.company.aiops.analysis.evidence.InterfaceLogEvidence;
import com.company.aiops.analysis.evidence.MetricEvidence;
import com.company.aiops.analysis.evidence.ProviderCollectionResult;
import com.company.aiops.analysis.evidence.RuntimeLogEvidence;
import com.company.aiops.analysis.evidence.TraceEvidence;
import com.company.aiops.analysis.result.AnalysisMode;
import com.company.aiops.analysis.result.EvidenceReference;
import com.company.aiops.analysis.result.MissingEvidence;
import com.company.aiops.analysis.rule.RuleAnalysisTestFixtures;
import com.company.aiops.analysis.rule.model.CandidateRootCause;
import com.company.aiops.analysis.rule.model.ErrorCodeAggregate;
import com.company.aiops.analysis.rule.model.RuleAnalysisEvidence;
import com.company.aiops.analysis.rule.model.RuleFinding;
import com.company.aiops.common.config.AiopsProperties;
import com.company.aiops.provider.model.ContainerMetrics;
import com.company.aiops.provider.model.CseMetrics;
import com.company.aiops.provider.model.DatabaseMetrics;
import com.company.aiops.provider.model.DcsMetrics;
import com.company.aiops.provider.model.InterfaceBaseline;
import com.company.aiops.provider.model.MqMetrics;
import com.company.aiops.provider.model.ResourceMapping;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultRuleBasedFallbackAnalyzerTests {

    private final AiopsProperties aiopsProperties = RuleAnalysisTestFixtures.defaultProperties();
    private final DefaultRuleBasedFallbackAnalyzer analyzer = new DefaultRuleBasedFallbackAnalyzer(aiopsProperties);

    @Test
    void analyzeBuildsRuleOnlyResultFromCompressedEvidence() {
        AlertEvent alert = RuleAnalysisTestFixtures.errorRateAlert(6.0);
        ProviderCollectionResult collection = completeCollection(alert);
        RuleAnalysisEvidence ruleAnalysis = new RuleAnalysisEvidence(
                List.of(new ErrorCodeAggregate(
                        "error.top.1",
                        "BIZ_001",
                        5,
                        1.0,
                        2,
                        2,
                        false,
                        true,
                        List.of("validation failed")
                )),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new RuleFinding(
                        "BUSINESS_ERROR_CODE_NOT_MASKED",
                        0.55,
                        "Top error code looks like business validation failure.",
                        List.of("error.top.1")
                )),
                List.of(new CandidateRootCause(
                        "BUSINESS_ERROR_CODE_NOT_MASKED",
                        55,
                        0.55,
                        List.of("error.top.1"),
                        "top errorCode share >= 80%; representative errorMsg matches business-validation semantics."
                )),
                List.of(
                        new EvidenceReference("alert.request.count", "ALERT", "Current request count"),
                        new EvidenceReference("error.top.1", "ERROR_CODE", "Top error code aggregate")
                ),
                List.of("Trace topology is inferred from spanId/parentSpanId instead of a dedicated call-tree API.")
        );
        CompressedEvidenceBundle compressedEvidence = compressedEvidence(alert, collection, ruleAnalysis);

        var result = analyzer.analyze(new RuleBasedAnalysisRequest(
                "task-1",
                collection,
                ruleAnalysis,
                compressedEvidence,
                AnalysisMode.RULE_BASED_ONLY,
                "DISABLED",
                null
        ));

        assertThat(result.analysisMode()).isEqualTo(AnalysisMode.RULE_BASED_ONLY);
        assertThat(result.llmUsed()).isFalse();
        assertThat(result.llmStatus()).isEqualTo("DISABLED");
        assertThat(result.degraded()).isFalse();
        assertThat(result.ruleEngineVersion()).isEqualTo("fallback-rules-v1");
        assertThat(result.summary()).contains("LLM is disabled");
        assertThat(result.rootCauses()).singleElement().satisfies(rootCause -> {
            assertThat(rootCause.causeType()).isEqualTo("BUSINESS_ERROR_CODE_NOT_MASKED");
            assertThat(rootCause.evidenceIds()).contains("error.top.1");
            assertThat(rootCause.source()).isEqualTo("RULE_ENGINE");
        });
        assertThat(result.recommendations()).isNotEmpty();
        assertThat(result.evidenceRefs()).extracting(EvidenceReference::evidenceId)
                .contains("alert.request.count", "error.top.1");
    }

    @Test
    void analyzeBuildsDegradedResultWhenCriticalProviderEvidenceIsMissing() {
        AlertEvent alert = RuleAnalysisTestFixtures.errorRateAlert(20.0);
        ProviderCollectionResult collection = new ProviderCollectionResult(
                context(alert),
                BaselineEvidence.available(InterfaceBaseline.empty()),
                InterfaceLogEvidence.missing("provider timeout"),
                TraceEvidence.missing("trace skipped because interface logs are unavailable"),
                RuntimeLogEvidence.missing("runtime logs skipped because trace ids are unavailable"),
                MetricEvidence.available(
                        ContainerMetrics.empty(),
                        CseMetrics.empty(),
                        DatabaseMetrics.empty(),
                        DcsMetrics.empty(),
                        MqMetrics.empty(),
                        null
                ),
                List.of(new MissingEvidence(
                        "interfaceLog",
                        "provider timeout",
                        true,
                        "Without interface logs, the rule engine cannot confirm the request-level failure pattern."
                )),
                true,
                true,
                "Critical provider evidence is unavailable: interfaceLog (provider timeout).",
                List.of()
        );

        var result = analyzer.analyze(new RuleBasedAnalysisRequest(
                "task-2",
                collection,
                null,
                null,
                AnalysisMode.RULE_BASED_DEGRADED,
                "SKIPPED_BY_PROVIDER_FAILURE",
                collection.degradedReason()
        ));

        assertThat(result.analysisMode()).isEqualTo(AnalysisMode.RULE_BASED_DEGRADED);
        assertThat(result.degraded()).isTrue();
        assertThat(result.degradedByProviderFailure()).isTrue();
        assertThat(result.ruleEngineVersion()).isEqualTo("fallback-rules-v1");
        assertThat(result.rootCauses()).singleElement().satisfies(rootCause -> {
            assertThat(rootCause.causeType()).isEqualTo("INSUFFICIENT_CRITICAL_EVIDENCE");
            assertThat(rootCause.evidenceIds()).isNotEmpty();
        });
        assertThat(result.recommendations()).extracting("actionType").contains("CHECK_PROVIDER");
        assertThat(result.evidenceRefs()).extracting(EvidenceReference::evidenceId)
                .anyMatch(evidenceId -> evidenceId.startsWith("missing.provider.interfacelog"));
        assertThat(result.insufficientEvidence()).anyMatch(item -> item.contains("SKIPPED_BY_PROVIDER_FAILURE"));
    }

    @Test
    void analyzeBuildsServiceErrorLogFallbackFromRuntimeLogRuleEvidence() {
        AlertEvent alert = RuleAnalysisTestFixtures.serviceErrorLogAlert();
        ProviderCollectionResult collection = completeServiceLogCollection(alert);
        RuleAnalysisEvidence ruleAnalysis = new RuleAnalysisEvidence(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new RuleFinding(
                        "CURRENT_SERVICE_RUNTIME_EXCEPTION",
                        0.68,
                        "matched ERROR runtime log",
                        List.of("runtime.log.1")
                )),
                List.of(new CandidateRootCause(
                        "CURRENT_SERVICE_RUNTIME_EXCEPTION",
                        68,
                        0.68,
                        List.of("runtime.log.1", "runtime.query.attempt.1"),
                        "matched current service error log"
                )),
                List.of(
                        new EvidenceReference("runtime.log.1", "RUNTIME_LOG", "matched runtime log"),
                        new EvidenceReference("runtime.query.attempt.1", "RUNTIME_LOG_QUERY", "selected query")
                ),
                List.of()
        );

        var result = analyzer.analyze(new RuleBasedAnalysisRequest(
                "task-service-log",
                collection,
                ruleAnalysis,
                null,
                AnalysisMode.RULE_BASED_ONLY,
                "DISABLED",
                null
        ));

        assertThat(result.summary()).contains("service error log");
        assertThat(result.impact().description()).contains("failureCount");
        assertThat(result.rootCauses()).singleElement()
                .satisfies(rootCause -> assertThat(rootCause.causeType())
                        .isEqualTo("CURRENT_SERVICE_RUNTIME_EXCEPTION"));
        assertThat(result.recommendations()).extracting("actionType")
                .contains("CHECK_LOG", "CHECK_TRACE");
    }

    @Test
    void analyzeBuildsDegradedServiceLogResultWhenRuntimeLogLookupMisses() {
        AlertEvent alert = RuleAnalysisTestFixtures.serviceErrorLogAlert();
        ProviderCollectionResult collection = new ProviderCollectionResult(
                context(alert),
                BaselineEvidence.available(InterfaceBaseline.empty()),
                InterfaceLogEvidence.missing("service error log alert has no interfaceName"),
                TraceEvidence.missing("runtime log trace ids unavailable"),
                RuntimeLogEvidence.missing("No ERROR runtime logs matched alert message queries"),
                MetricEvidence.available(ContainerMetrics.empty(), CseMetrics.empty(),
                        DatabaseMetrics.empty(), DcsMetrics.empty(), MqMetrics.empty(), null),
                List.of(new MissingEvidence(
                        "runtimeLog",
                        "No ERROR runtime logs matched alert message queries",
                        true,
                        "Cannot locate the ERROR runtime log context that triggered this service-level alert."
                )),
                true,
                true,
                "Could not locate ERROR runtime log context from alert message queries.",
                List.of()
        );

        var result = analyzer.analyze(new RuleBasedAnalysisRequest(
                "task-service-log-miss",
                collection,
                null,
                null,
                AnalysisMode.RULE_BASED_DEGRADED,
                "SKIPPED_BY_PROVIDER_FAILURE",
                collection.degradedReason()
        ));

        assertThat(result.degraded()).isTrue();
        assertThat(result.rootCauses()).singleElement()
                .satisfies(rootCause -> assertThat(rootCause.causeType()).isEqualTo("UNKNOWN"));
        assertThat(result.summary()).contains("could not locate ERROR runtime log context");
        assertThat(result.recommendations()).extracting("actionType").contains("CHECK_LOG");
    }

    private ProviderCollectionResult completeCollection(AlertEvent alert) {
        return new ProviderCollectionResult(
                context(alert),
                BaselineEvidence.available(InterfaceBaseline.empty()),
                InterfaceLogEvidence.available(List.of(), List.of()),
                TraceEvidence.available(List.of()),
                RuntimeLogEvidence.available(List.of()),
                MetricEvidence.available(
                        ContainerMetrics.empty(),
                        CseMetrics.empty(),
                        DatabaseMetrics.empty(),
                        DcsMetrics.empty(),
                        MqMetrics.empty(),
                        null
                ),
                List.of(),
                false,
                false,
                null,
                List.of()
        );
    }

    private ProviderCollectionResult completeServiceLogCollection(AlertEvent alert) {
        return new ProviderCollectionResult(
                context(alert),
                BaselineEvidence.available(InterfaceBaseline.empty()),
                InterfaceLogEvidence.missing("service error log alert has no interfaceName"),
                TraceEvidence.available(List.of()),
                RuntimeLogEvidence.availableMessageQuery(
                        List.of(),
                        "loginAppointment failed",
                        List.of(new RuntimeLogEvidence.QueryAttempt(
                                "runtime.query.attempt.1",
                                "loginAppointment failed",
                                "ERROR",
                                1,
                                true
                        )),
                        List.of("trace-log-1")
                ),
                MetricEvidence.available(
                        ContainerMetrics.empty(),
                        CseMetrics.empty(),
                        DatabaseMetrics.empty(),
                        DcsMetrics.empty(),
                        MqMetrics.empty(),
                        null
                ),
                List.of(),
                false,
                false,
                null,
                List.of()
        );
    }

    private AnalysisContext context(AlertEvent alert) {
        return new AnalysisContext(
                alert,
                new TimeWindow(alert.metricOccurTime().minus(Duration.ofMinutes(10)), alert.metricOccurTime().plus(Duration.ofMinutes(2))),
                new TimeWindow(alert.metricOccurTime().minus(Duration.ofDays(1)).minus(Duration.ofMinutes(5)), alert.metricOccurTime().minus(Duration.ofDays(1)).plus(Duration.ofMinutes(5))),
                ResourceMapping.empty(alert.serviceName(), alert.cluster()),
                new AnalysisOptions(2000, 20, 20, 50, 500, 10, 10, 5, 8, 2, "ERROR", false, true)
        );
    }

    private CompressedEvidenceBundle compressedEvidence(
            AlertEvent alert,
            ProviderCollectionResult collection,
            RuleAnalysisEvidence ruleAnalysis
    ) {
        return new CompressedEvidenceBundle(
                new CompressedEvidenceBundle.AlertSnapshot(
                        alert.category(),
                        alert.serviceName(),
                        alert.interfaceName(),
                        alert.cluster(),
                        alert.metricOccurTime(),
                        "alert.request.count",
                        alert.triggerValues().requestCount(),
                        "alert.fail.rate",
                        alert.triggerValues().failRate(),
                        "alert.avg.latency",
                        alert.triggerValues().avgLatencyMs(),
                        "alert.p99.latency",
                        alert.triggerValues().p99LatencyMs(),
                        "alert.failure.count",
                        null,
                        "alert.log.level",
                        null,
                        "alert.log.message",
                        null,
                        "alert.log.normalized_query",
                        null
                ),
                new CompressedEvidenceBundle.BaselineSnapshot(
                        true,
                        null,
                        "baseline.request.count",
                        "baseline.fail.rate",
                        "baseline.avg.latency",
                        "baseline.p99.latency",
                        InterfaceBaseline.empty()
                ),
                new CompressedEvidenceBundle.InterfaceLogSnapshot(
                        true,
                        null,
                        "interface.request.count",
                        6,
                        "interface.failure.rate",
                        5,
                        0.83,
                        "interface.latency.profile",
                        0L,
                        0L,
                        0L,
                        0L,
                        List.of("trace-1"),
                        ruleAnalysis.errorCodeDistribution(),
                        List.of()
                ),
                new CompressedEvidenceBundle.TraceSnapshot(true, null, List.of("trace-1"), List.of(), List.of()),
                new CompressedEvidenceBundle.RuntimeLogSnapshot(true, null, List.of(), false, null, List.of(), List.of()),
                new CompressedEvidenceBundle.MetricSnapshot(
                        true,
                        null,
                        ContainerMetrics.empty(),
                        CseMetrics.empty(),
                        DatabaseMetrics.empty(),
                        DcsMetrics.empty(),
                        MqMetrics.empty(),
                        List.of()
                ),
                ResourceMapping.empty(alert.serviceName(), alert.cluster()),
                new CompressedEvidenceBundle.RuleSnapshot(
                        ruleAnalysis.ruleFindings(),
                        ruleAnalysis.candidateRootCauses()
                ),
                List.of(
                        new EvidenceReference("alert.request.count", "ALERT", "Current request count"),
                        new EvidenceReference("error.top.1", "ERROR_CODE", "Top error code aggregate")
                ),
                collection.missingEvidence(),
                ruleAnalysis.insufficientEvidence()
        );
    }
}
