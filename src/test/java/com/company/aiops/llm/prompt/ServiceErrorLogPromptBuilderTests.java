package com.company.aiops.llm.prompt;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.analysis.bundle.CompressedEvidenceBundle;
import com.company.aiops.analysis.result.EvidenceReference;
import com.company.aiops.analysis.rule.model.CandidateRootCause;
import com.company.aiops.analysis.rule.model.RuleFinding;
import com.company.aiops.common.json.JsonMapper;
import com.company.aiops.provider.model.ContainerMetrics;
import com.company.aiops.provider.model.CseMetrics;
import com.company.aiops.provider.model.DatabaseMetrics;
import com.company.aiops.provider.model.DcsMetrics;
import com.company.aiops.provider.model.InterfaceBaseline;
import com.company.aiops.provider.model.MqMetrics;
import com.company.aiops.provider.model.ResourceMapping;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceErrorLogPromptBuilderTests {

    @Test
    void buildMessagesHighlightsMessageQueryTraceAndFailureCountRules() {
        ServiceErrorLogPromptBuilder builder = new ServiceErrorLogPromptBuilder(
                new JsonMapper(new ObjectMapper().findAndRegisterModules())
        );

        String combined = String.join("\n", builder.buildMessages(compressedEvidence(), null).stream()
                .map(message -> message.content())
                .toList());

        assertThat(builder.supportedCategory()).isEqualTo(AlertCategory.SERVICE_ERROR_LOG);
        assertThat(combined).contains("service error log alert");
        assertThat(combined).contains("ERROR runtime log context");
        assertThat(combined).contains("matchedLogQuery");
        assertThat(combined).contains("traceId");
        assertThat(combined).contains("failureCount");
        assertThat(combined).contains("CURRENT_SERVICE_RUNTIME_EXCEPTION");
        assertThat(combined).contains("REPEATED_BUSINESS_LOG_ERROR");
        assertThat(combined).contains("Evidence JSON:");
    }

    private CompressedEvidenceBundle compressedEvidence() {
        Instant occurTime = Instant.parse("2026-06-22T05:42:00Z");
        return new CompressedEvidenceBundle(
                new CompressedEvidenceBundle.AlertSnapshot(
                        AlertCategory.SERVICE_ERROR_LOG,
                        "ams_front",
                        null,
                        "cluster2",
                        occurTime,
                        "alert.request.count",
                        null,
                        "alert.fail.rate",
                        null,
                        "alert.avg.latency",
                        null,
                        "alert.p99.latency",
                        null,
                        "alert.failure.count",
                        2.0,
                        "alert.log.level",
                        "ERROR",
                        "alert.log.message",
                        "DEFAULT prebook loginAppointment failed",
                        "alert.log.normalized_query",
                        "prebook loginAppointment failed"
                ),
                new CompressedEvidenceBundle.BaselineSnapshot(true, null,
                        "baseline.request.count", "baseline.fail.rate",
                        "baseline.avg.latency", "baseline.p99.latency", InterfaceBaseline.empty()),
                new CompressedEvidenceBundle.InterfaceLogSnapshot(false, "service alert has no interface",
                        "interface.request.count", 0, "interface.failure.rate", 0, 0.0,
                        "interface.latency.profile", 0L, 0L, 0L, 0L, List.of(), List.of(), List.of()),
                new CompressedEvidenceBundle.TraceSnapshot(true, null, List.of("trace-log-1"), List.of(), List.of()),
                new CompressedEvidenceBundle.RuntimeLogSnapshot(
                        true,
                        null,
                        List.of(),
                        true,
                        "loginAppointment failed",
                        List.of(new CompressedEvidenceBundle.RuntimeLogQueryAttempt(
                                "runtime.query.attempt.1", "loginAppointment failed", "ERROR", 2, true)),
                        List.of("trace-log-1")
                ),
                new CompressedEvidenceBundle.MetricSnapshot(true, null,
                        ContainerMetrics.empty(), CseMetrics.empty(), DatabaseMetrics.empty(),
                        DcsMetrics.empty(), MqMetrics.empty(), List.of()),
                ResourceMapping.empty("ams_front", "cluster2"),
                new CompressedEvidenceBundle.RuleSnapshot(
                        List.of(new RuleFinding(
                                "CURRENT_SERVICE_RUNTIME_EXCEPTION",
                                0.68,
                                "matched runtime exception",
                                List.of("runtime.query.attempt.1")
                        )),
                        List.of(new CandidateRootCause(
                                "CURRENT_SERVICE_RUNTIME_EXCEPTION",
                                68,
                                0.68,
                                List.of("runtime.query.attempt.1"),
                                "matched ERROR runtime log query"
                        ))
                ),
                List.of(new EvidenceReference("runtime.query.attempt.1", "RUNTIME_LOG_QUERY", "matched query")),
                List.of(),
                List.of()
        );
    }
}
