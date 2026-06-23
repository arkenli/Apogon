package com.company.aiops.llm.service;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.alert.model.AlertEvent;
import com.company.aiops.alert.model.AlertTriggerValues;
import com.company.aiops.analysis.bundle.CompressedEvidenceBundle;
import com.company.aiops.analysis.evidence.BaselineEvidence;
import com.company.aiops.analysis.evidence.InterfaceLogEvidence;
import com.company.aiops.analysis.evidence.MetricEvidence;
import com.company.aiops.analysis.evidence.ProviderCollectionResult;
import com.company.aiops.analysis.evidence.RuntimeLogEvidence;
import com.company.aiops.analysis.evidence.TraceEvidence;
import com.company.aiops.analysis.result.AnalysisMode;
import com.company.aiops.analysis.result.EvidenceReference;
import com.company.aiops.analysis.rule.RuleAnalysisTestFixtures;
import com.company.aiops.common.config.AiopsProperties;
import com.company.aiops.common.json.JsonMapper;
import com.company.aiops.llm.client.LlmClient;
import com.company.aiops.llm.client.LlmRequest;
import com.company.aiops.llm.client.LlmResponse;
import com.company.aiops.llm.client.mock.MockLlmClient;
import com.company.aiops.llm.prompt.PromptBuilder;
import com.company.aiops.llm.prompt.RpcErrorRatePromptBuilder;
import com.company.aiops.llm.prompt.RpcLatencyPromptBuilder;
import com.company.aiops.llm.prompt.ServiceErrorLogPromptBuilder;
import com.company.aiops.llm.validator.DefaultLlmOutputValidator;
import com.company.aiops.persistence.repository.LlmCallLogRepository;
import com.company.aiops.provider.model.ContainerMetrics;
import com.company.aiops.provider.model.CseMetrics;
import com.company.aiops.provider.model.DatabaseMetrics;
import com.company.aiops.provider.model.DcsMetrics;
import com.company.aiops.provider.model.InterfaceBaseline;
import com.company.aiops.provider.model.MqMetrics;
import com.company.aiops.provider.model.ResourceMapping;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultLlmAnalysisServiceTests {

    @Test
    void analyzeRetriesOnceAfterInvalidJsonAndThenReturnsLlmAssistedResult() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        JsonMapper jsonMapper = new JsonMapper(objectMapper);
        InMemoryLlmCallLogRepository llmCallLogRepository = new InMemoryLlmCallLogRepository();
        SequenceLlmClient llmClient = new SequenceLlmClient(List.of(
                new LlmResponse("{\"summary\":", "mock-model", 120, 20, Duration.ofMillis(30), "stop"),
                new LlmResponse("""
                        {
                          "summary": "valid summary after retry",
                          "impact": {
                            "level": "LOW",
                            "description": "limited impact"
                          },
                          "rootCauses": [
                            {
                              "causeType": "BUSINESS_ERROR_CODE_NOT_MASKED",
                              "description": "business validation failure likely needs masking",
                              "confidence": 0.71,
                              "evidenceIds": ["alert.request.count"],
                              "reasoning": "retry produced valid JSON and reused compressed evidence ids"
                            }
                          ],
                          "recommendations": [
                            {
                              "priority": "P1",
                              "actionType": "CHECK_LOG",
                              "description": "inspect representative business validation errors",
                              "target": "demo.Interface.query"
                            }
                          ],
                          "overallConfidence": 0.71,
                          "insufficientEvidence": []
                        }
                        """, "mock-model", 120, 60, Duration.ofMillis(25), "stop")
        ));
        List<PromptBuilder> promptBuilders = List.of(
                new RpcErrorRatePromptBuilder(jsonMapper),
                new RpcLatencyPromptBuilder(jsonMapper),
                new ServiceErrorLogPromptBuilder(jsonMapper)
        );
        DefaultLlmAnalysisService service = new DefaultLlmAnalysisService(
                properties(),
                jsonMapper,
                Map.of("mock", llmClient),
                promptBuilders,
                new DefaultLlmOutputValidator(objectMapper),
                llmCallLogRepository
        );

        var result = service.analyze("task-1", collection(), compressedEvidence());

        assertThat(result.analysisMode()).isEqualTo(AnalysisMode.LLM_ASSISTED);
        assertThat(result.llmStatus()).isEqualTo("SUCCESS");
        assertThat(result.rootCauses()).singleElement().satisfies(rootCause ->
                assertThat(rootCause.evidenceIds()).containsExactly("alert.request.count"));
        assertThat(llmCallLogRepository.logs)
                .extracting(log -> log.callStatus().name())
                .containsExactly("INVALID_JSON", "SUCCESS");
    }

    @Test
    void analyzeSupportsServiceErrorLogPromptBuilder() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        JsonMapper jsonMapper = new JsonMapper(objectMapper);
        InMemoryLlmCallLogRepository llmCallLogRepository = new InMemoryLlmCallLogRepository();
        LlmClient llmClient = new MockLlmClient(objectMapper);
        DefaultLlmAnalysisService service = new DefaultLlmAnalysisService(
                properties(),
                jsonMapper,
                Map.of("mock", llmClient),
                List.of(
                        new RpcErrorRatePromptBuilder(jsonMapper),
                        new RpcLatencyPromptBuilder(jsonMapper),
                        new ServiceErrorLogPromptBuilder(jsonMapper)
                ),
                new DefaultLlmOutputValidator(objectMapper),
                llmCallLogRepository
        );

        var result = service.analyze(
                "task-service-log",
                serviceLogCollection(),
                serviceLogCompressedEvidence()
        );

        assertThat(result.category()).isEqualTo(AlertCategory.SERVICE_ERROR_LOG);
        assertThat(result.analysisMode()).isEqualTo(AnalysisMode.LLM_ASSISTED);
        assertThat(result.rootCauses()).singleElement()
                .satisfies(rootCause -> assertThat(rootCause.causeType())
                        .isEqualTo("CURRENT_SERVICE_RUNTIME_EXCEPTION"));
        assertThat(llmCallLogRepository.logs).hasSize(1);
    }

    private AiopsProperties properties() {
        return new AiopsProperties(
                RuleAnalysisTestFixtures.defaultProperties().analysis(),
                RuleAnalysisTestFixtures.defaultProperties().provider(),
                new AiopsProperties.Llm(
                        true,
                        "mock",
                        "mock-model",
                        0.1,
                        1000,
                        Duration.ofSeconds(10),
                        true,
                        "fallback-rules-v1",
                        null,
                        null
                ),
                RuleAnalysisTestFixtures.defaultProperties().thresholds()
        );
    }

    private ProviderCollectionResult collection() {
        Instant occurTime = Instant.parse("2026-04-21T02:33:00Z");
        AlertEvent alert = new AlertEvent(
                "alert-1",
                AlertCategory.RPC_ERROR_RATE,
                "OPEN",
                "MAJOR",
                "demo-service",
                "demo.Interface.query",
                "cluster1",
                "site",
                "tenant",
                "application",
                "demo alert",
                "metric",
                "AIOps@rate",
                "raw",
                occurTime,
                occurTime,
                new AlertTriggerValues(6.0, 0.8, null, null, null, null, null, null, null, "demo", "raw")
        );
        return new ProviderCollectionResult(
                RuleAnalysisTestFixtures.collection(
                        alert,
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
                        )
                ).context(),
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

    private CompressedEvidenceBundle compressedEvidence() {
        Instant occurTime = Instant.parse("2026-04-21T02:33:00Z");
        return new CompressedEvidenceBundle(
                new CompressedEvidenceBundle.AlertSnapshot(
                        AlertCategory.RPC_ERROR_RATE,
                        "demo-service",
                        "demo.Interface.query",
                        "cluster1",
                        occurTime,
                        "alert.request.count",
                        6.0,
                        "alert.fail.rate",
                        0.8,
                        "alert.avg.latency",
                        null,
                        "alert.p99.latency",
                        null,
                        "alert.failure.count",
                        null,
                        "alert.log.level",
                        null,
                        "alert.log.message",
                        null,
                        "alert.log.normalized_query",
                        null
                ),
                new CompressedEvidenceBundle.BaselineSnapshot(true, null, "baseline.request.count", "baseline.fail.rate", "baseline.avg.latency", "baseline.p99.latency", InterfaceBaseline.empty()),
                new CompressedEvidenceBundle.InterfaceLogSnapshot(true, null, "interface.request.count", 6, "interface.failure.rate", 5, 0.8, "interface.latency.profile", 0L, 0L, 0L, 0L, List.of(), List.of(), List.of()),
                new CompressedEvidenceBundle.TraceSnapshot(true, null, List.of(), List.of(), List.of()),
                new CompressedEvidenceBundle.RuntimeLogSnapshot(true, null, List.of(), false, null, List.of(), List.of()),
                new CompressedEvidenceBundle.MetricSnapshot(true, null, ContainerMetrics.empty(), CseMetrics.empty(), DatabaseMetrics.empty(), DcsMetrics.empty(), MqMetrics.empty(), List.of()),
                ResourceMapping.empty("demo-service", "cluster1"),
                new CompressedEvidenceBundle.RuleSnapshot(List.of(), List.of()),
                List.of(new EvidenceReference("alert.request.count", "ALERT", "request count")),
                List.of(),
                List.of()
        );
    }

    private ProviderCollectionResult serviceLogCollection() {
        AlertEvent alert = RuleAnalysisTestFixtures.serviceErrorLogAlert();
        return RuleAnalysisTestFixtures.collection(
                alert,
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
                )
        );
    }

    private CompressedEvidenceBundle serviceLogCompressedEvidence() {
        AlertEvent alert = RuleAnalysisTestFixtures.serviceErrorLogAlert();
        return new CompressedEvidenceBundle(
                new CompressedEvidenceBundle.AlertSnapshot(
                        AlertCategory.SERVICE_ERROR_LOG,
                        alert.serviceName(),
                        alert.interfaceName(),
                        alert.cluster(),
                        alert.metricOccurTime(),
                        "alert.request.count",
                        null,
                        "alert.fail.rate",
                        null,
                        "alert.avg.latency",
                        null,
                        "alert.p99.latency",
                        null,
                        "alert.failure.count",
                        alert.triggerValues().failureCount(),
                        "alert.log.level",
                        alert.triggerValues().logLevel(),
                        "alert.log.message",
                        alert.triggerValues().logMessage(),
                        "alert.log.normalized_query",
                        alert.triggerValues().normalizedLogQuery()
                ),
                new CompressedEvidenceBundle.BaselineSnapshot(true, null, "baseline.request.count", "baseline.fail.rate", "baseline.avg.latency", "baseline.p99.latency", InterfaceBaseline.empty()),
                new CompressedEvidenceBundle.InterfaceLogSnapshot(false, "service error log alert has no interfaceName", "interface.request.count", 0, "interface.failure.rate", 0, 0.0, "interface.latency.profile", 0L, 0L, 0L, 0L, List.of(), List.of(), List.of()),
                new CompressedEvidenceBundle.TraceSnapshot(true, null, List.of("trace-log-1"), List.of(), List.of()),
                new CompressedEvidenceBundle.RuntimeLogSnapshot(
                        true,
                        null,
                        List.of(),
                        true,
                        "loginAppointment failed",
                        List.of(new CompressedEvidenceBundle.RuntimeLogQueryAttempt(
                                "runtime.query.attempt.1",
                                "loginAppointment failed",
                                "ERROR",
                                1,
                                true
                        )),
                        List.of("trace-log-1")
                ),
                new CompressedEvidenceBundle.MetricSnapshot(true, null, ContainerMetrics.empty(), CseMetrics.empty(), DatabaseMetrics.empty(), DcsMetrics.empty(), MqMetrics.empty(), List.of()),
                ResourceMapping.empty(alert.serviceName(), alert.cluster()),
                new CompressedEvidenceBundle.RuleSnapshot(List.of(), List.of()),
                List.of(new EvidenceReference("runtime.query.attempt.1", "RUNTIME_LOG_QUERY", "matched query")),
                List.of(),
                List.of()
        );
    }

    private static final class SequenceLlmClient implements LlmClient {

        private final List<LlmResponse> responses;
        private int index;

        private SequenceLlmClient(List<LlmResponse> responses) {
            this.responses = new ArrayList<>(responses);
        }

        @Override
        public LlmResponse complete(LlmRequest request) {
            LlmResponse response = responses.get(Math.min(index, responses.size() - 1));
            index++;
            return response;
        }
    }

    private static final class InMemoryLlmCallLogRepository implements LlmCallLogRepository {

        private final List<LlmCallLog> logs = new ArrayList<>();

        @Override
        public void save(LlmCallLog llmCallLog) {
            logs.add(llmCallLog);
        }

        @Override
        public List<LlmCallLog> findByTaskId(String taskId) {
            return logs.stream().filter(log -> log.taskId().equals(taskId)).toList();
        }
    }
}
