package com.company.aiops.analysis.rule.log;

import com.company.aiops.analysis.evidence.BaselineEvidence;
import com.company.aiops.analysis.evidence.InterfaceLogEvidence;
import com.company.aiops.analysis.evidence.MetricEvidence;
import com.company.aiops.analysis.evidence.ProviderCollectionResult;
import com.company.aiops.analysis.evidence.RuntimeLogEvidence;
import com.company.aiops.analysis.evidence.TraceEvidence;
import com.company.aiops.analysis.evidence.TraceRecord;
import com.company.aiops.analysis.result.EvidenceReference;
import com.company.aiops.analysis.rule.RuleAnalysisTestFixtures;
import com.company.aiops.analysis.rule.model.CandidateRootCause;
import com.company.aiops.analysis.rule.model.RuleAnalysisEvidence;
import com.company.aiops.analysis.rule.model.RuleFinding;
import com.company.aiops.provider.model.InterfaceLogEntry;
import com.company.aiops.provider.model.RuntimeLogEntry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultErrorLogRuleAnalyzerTests {

    private final DefaultErrorLogRuleAnalyzer analyzer = new DefaultErrorLogRuleAnalyzer();

    @Test
    void analyzeFindsCurrentServiceRuntimeExceptionFromMatchedErrorLogsAndTrace() {
        RuntimeLogEntry logEntry = runtimeLog(
                "trace-service-fail",
                "ams-front-1",
                "AppointmentLoginException"
        );
        RuntimeLogEvidence runtimeLogEvidence = RuntimeLogEvidence.availableMessageQuery(
                List.of(logEntry),
                "prebook loginAppointment failed",
                List.of(new RuntimeLogEvidence.QueryAttempt(
                        "runtime.query.attempt.1",
                        "prebook loginAppointment failed",
                        "ERROR",
                        1,
                        true
                )),
                List.of("trace-service-fail")
        );
        TraceEvidence traceEvidence = TraceEvidence.available(List.of(new TraceRecord(
                "trace-service-fail",
                List.of(new InterfaceLogEntry(
                        "ams_front",
                        "loginAppointment",
                        "trace-service-fail",
                        "span-current",
                        null,
                        "uid-1",
                        false,
                        "AppointmentLoginException",
                        "DEFAULT prebook loginAppointment failed",
                        230L,
                        Instant.parse("2026-04-22T03:15:01Z")
                ))
        )));

        RuleAnalysisEvidence evidence = analyzer.analyze(collection(runtimeLogEvidence, traceEvidence));

        assertThat(evidence.ruleFindings())
                .extracting(RuleFinding::type)
                .contains("CURRENT_SERVICE_RUNTIME_EXCEPTION");
        CandidateRootCause candidate = candidate(evidence, "CURRENT_SERVICE_RUNTIME_EXCEPTION");
        assertThat(candidate.evidenceIds()).contains("runtime.log.1", "trace.failure.1");
        assertThat(evidence.evidenceRefs())
                .extracting(EvidenceReference::evidenceId)
                .contains("runtime.query.attempt.1", "runtime.log.1", "trace.failure.1");
        assertReferencedEvidenceIdsResolve(evidence);
    }

    @Test
    void analyzeReturnsUnknownWhenRuntimeLogContextIsMissing() {
        String reason = "No ERROR runtime logs matched alert message queries";

        RuleAnalysisEvidence evidence = analyzer.analyze(collection(
                RuntimeLogEvidence.missing(reason),
                TraceEvidence.missing("trace lookup skipped because runtime logs were missing")
        ));

        assertThat(evidence.ruleFindings())
                .extracting(RuleFinding::type)
                .containsExactly("UNKNOWN");
        CandidateRootCause candidate = candidate(evidence, "UNKNOWN");
        assertThat(candidate.reasoning()).contains(reason);
        assertThat(evidence.insufficientEvidence())
                .anySatisfy(message -> assertThat(message).contains("ERROR runtime logs"));
        assertReferencedEvidenceIdsResolve(evidence);
    }

    @Test
    void analyzeFindsSingleInstanceAbnormalWhenLogsConcentrateOnOneInstance() {
        RuntimeLogEvidence runtimeLogEvidence = RuntimeLogEvidence.availableMessageQuery(
                List.of(
                        runtimeLog("trace-one", "ams-front-7", null),
                        runtimeLog("trace-two", "ams-front-7", null)
                ),
                "prebook loginAppointment failed",
                List.of(new RuntimeLogEvidence.QueryAttempt(
                        "runtime.query.attempt.1",
                        "prebook loginAppointment failed",
                        "ERROR",
                        2,
                        true
                )),
                List.of("trace-one", "trace-two")
        );

        RuleAnalysisEvidence evidence = analyzer.analyze(collection(
                runtimeLogEvidence,
                TraceEvidence.available(List.of())
        ));

        assertThat(evidence.ruleFindings())
                .extracting(RuleFinding::type)
                .contains("SINGLE_INSTANCE_ABNORMAL");
        CandidateRootCause candidate = candidate(evidence, "SINGLE_INSTANCE_ABNORMAL");
        assertThat(candidate.evidenceIds()).contains("runtime.instance.1");
        assertThat(evidence.evidenceRefs())
                .extracting(EvidenceReference::evidenceId)
                .contains("runtime.instance.1");
        assertReferencedEvidenceIdsResolve(evidence);
    }

    @Test
    void analyzeFindsSingleInstanceAbnormalFromErrorLogsWhenOtherLevelsUseOtherInstances() {
        RuntimeLogEvidence runtimeLogEvidence = RuntimeLogEvidence.availableMessageQuery(
                List.of(
                        runtimeLog("trace-error-one", "ams-front-7", "ERROR", null, Instant.parse("2026-04-22T03:15:01Z")),
                        runtimeLog("trace-warn-other", "ams-front-8", "WARN", "IllegalStateException", Instant.parse("2026-04-22T03:15:03Z")),
                        runtimeLog("trace-error-two", "ams-front-7", "ERROR", null, Instant.parse("2026-04-22T03:15:02Z"))
                ),
                "prebook loginAppointment failed",
                List.of(new RuntimeLogEvidence.QueryAttempt(
                        "runtime.query.attempt.1",
                        "prebook loginAppointment failed",
                        "ERROR",
                        2,
                        true
                )),
                List.of("trace-error-one", "trace-error-two")
        );

        RuleAnalysisEvidence evidence = analyzer.analyze(collection(
                runtimeLogEvidence,
                TraceEvidence.available(List.of())
        ));

        assertThat(evidence.ruleFindings())
                .extracting(RuleFinding::type)
                .contains("SINGLE_INSTANCE_ABNORMAL");
        CandidateRootCause candidate = candidate(evidence, "SINGLE_INSTANCE_ABNORMAL");
        assertThat(candidate.evidenceIds()).contains("runtime.instance.1");
        assertThat(candidate.reasoning()).contains("ERROR runtime logs");
        assertReferencedEvidenceIdsResolve(evidence);
    }

    @Test
    void analyzeReturnsUnknownForNonErrorRuntimeLogsOnly() {
        RuntimeLogEvidence runtimeLogEvidence = RuntimeLogEvidence.availableMessageQuery(
                List.of(
                        runtimeLog("trace-warn-one", "ams-front-7", "WARN", "IllegalStateException", Instant.parse("2026-04-22T03:15:01Z")),
                        runtimeLog("trace-info-two", "ams-front-7", "INFO", "NullPointerException", Instant.parse("2026-04-22T03:15:02Z"))
                ),
                "prebook loginAppointment failed",
                List.of(new RuntimeLogEvidence.QueryAttempt(
                        "runtime.query.attempt.1",
                        "prebook loginAppointment failed",
                        "ERROR",
                        0,
                        false
                )),
                List.of("trace-warn-one", "trace-info-two")
        );

        RuleAnalysisEvidence evidence = analyzer.analyze(collection(
                runtimeLogEvidence,
                TraceEvidence.available(List.of())
        ));

        assertThat(evidence.candidateRootCauses())
                .extracting(CandidateRootCause::causeType)
                .containsExactly("UNKNOWN");
        assertThat(evidence.ruleFindings())
                .extracting(RuleFinding::type)
                .containsExactly("UNKNOWN");
        assertThat(evidence.candidateRootCauses())
                .extracting(CandidateRootCause::causeType)
                .doesNotContain("CURRENT_SERVICE_RUNTIME_EXCEPTION", "SINGLE_INSTANCE_ABNORMAL");
        CandidateRootCause candidate = candidate(evidence, "UNKNOWN");
        assertThat(candidate.evidenceIds()).contains("runtime.log.1");
        assertThat(candidate.reasoning()).contains("No matched ERROR runtime logs");
        assertThat(evidence.insufficientEvidence())
                .anySatisfy(message -> assertThat(message).contains("No matched ERROR runtime logs"));
        assertReferencedEvidenceIdsResolve(evidence);
    }

    @Test
    void analyzeAssignsRuntimeLogEvidenceIdsUsingCompressorOrdering() {
        RuntimeLogEvidence runtimeLogEvidence = RuntimeLogEvidence.availableMessageQuery(
                List.of(
                        runtimeLog("trace-warn-newest", "ams-front-8", "WARN", null, Instant.parse("2026-04-22T03:15:05Z")),
                        runtimeLog("trace-error-older", "ams-front-9", "ERROR", null, Instant.parse("2026-04-22T03:15:01Z")),
                        runtimeLog("trace-error-newest", "ams-front-10", "ERROR", "IllegalStateException", Instant.parse("2026-04-22T03:15:04Z"))
                ),
                "prebook loginAppointment failed",
                List.of(new RuntimeLogEvidence.QueryAttempt(
                        "runtime.query.attempt.1",
                        "prebook loginAppointment failed",
                        "ERROR",
                        2,
                        true
                )),
                List.of("trace-error-older", "trace-error-newest")
        );

        RuleAnalysisEvidence evidence = analyzer.analyze(collection(
                runtimeLogEvidence,
                TraceEvidence.available(List.of())
        ));

        CandidateRootCause candidate = candidate(evidence, "CURRENT_SERVICE_RUNTIME_EXCEPTION");
        assertThat(candidate.evidenceIds()).contains("runtime.log.1");
        assertThat(evidenceRef(evidence, "runtime.log.1").description())
                .contains("trace-error-newest");
        assertReferencedEvidenceIdsResolve(evidence);
    }

    @Test
    void analyzeReturnsUnknownWhenLogsHaveNoHighConfidenceRuleHit() {
        RuntimeLogEvidence runtimeLogEvidence = RuntimeLogEvidence.availableMessageQuery(
                List.of(runtimeLog("trace-info", "ams-front-3", null)),
                "prebook loginAppointment failed",
                List.of(new RuntimeLogEvidence.QueryAttempt(
                        "runtime.query.attempt.1",
                        "prebook loginAppointment failed",
                        "ERROR",
                        1,
                        true
                )),
                List.of("trace-info")
        );

        RuleAnalysisEvidence evidence = analyzer.analyze(collection(
                runtimeLogEvidence,
                TraceEvidence.available(List.of())
        ));

        assertThat(evidence.ruleFindings())
                .extracting(RuleFinding::type)
                .containsExactly("UNKNOWN");
        CandidateRootCause candidate = candidate(evidence, "UNKNOWN");
        assertThat(candidate.evidenceIds()).contains("runtime.log.1");
        assertReferencedEvidenceIdsResolve(evidence);
    }

    private ProviderCollectionResult collection(RuntimeLogEvidence runtimeLogEvidence, TraceEvidence traceEvidence) {
        return RuleAnalysisTestFixtures.collection(
                RuleAnalysisTestFixtures.serviceErrorLogAlert(),
                BaselineEvidence.missing("baseline not required for service error log analysis"),
                InterfaceLogEvidence.missing("interface logs not required for service error log analysis"),
                traceEvidence,
                runtimeLogEvidence,
                MetricEvidence.missing("metrics not required for service error log analysis")
        );
    }

    private RuntimeLogEntry runtimeLog(String traceId, String instanceId, String exceptionType) {
        return runtimeLog(traceId, instanceId, "ERROR", exceptionType, Instant.parse("2026-04-22T03:15:01Z"));
    }

    private RuntimeLogEntry runtimeLog(
            String traceId,
            String instanceId,
            String level,
            String exceptionType,
            Instant timestamp
    ) {
        return new RuntimeLogEntry(
                "ams_front",
                "cluster2",
                instanceId,
                traceId,
                "span-current",
                level,
                "com.company.ams.AppointmentController",
                "http-nio-8080-exec-1",
                "DEFAULT prebook loginAppointment failed",
                exceptionType,
                "com.company.ams.AppointmentController.loginAppointment(AppointmentController.java:42)",
                timestamp
        );
    }

    private CandidateRootCause candidate(RuleAnalysisEvidence evidence, String causeType) {
        return evidence.candidateRootCauses().stream()
                .filter(candidate -> causeType.equals(candidate.causeType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing candidate: " + causeType));
    }

    private EvidenceReference evidenceRef(RuleAnalysisEvidence evidence, String evidenceId) {
        return evidence.evidenceRefs().stream()
                .filter(reference -> evidenceId.equals(reference.evidenceId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing evidence reference: " + evidenceId));
    }

    private void assertReferencedEvidenceIdsResolve(RuleAnalysisEvidence evidence) {
        List<String> referenceIds = evidence.evidenceRefs().stream()
                .map(EvidenceReference::evidenceId)
                .toList();
        evidence.ruleFindings().stream()
                .flatMap(finding -> finding.evidenceIds().stream())
                .forEach(evidenceId -> assertThat(referenceIds).contains(evidenceId));
        evidence.candidateRootCauses().stream()
                .flatMap(candidate -> candidate.evidenceIds().stream())
                .forEach(evidenceId -> assertThat(referenceIds).contains(evidenceId));
    }
}
