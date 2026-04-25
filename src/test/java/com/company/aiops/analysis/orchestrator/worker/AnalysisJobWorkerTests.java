package com.company.aiops.analysis.orchestrator.worker;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.alert.model.AlertEvent;
import com.company.aiops.alert.model.AlertTriggerValues;
import com.company.aiops.analysis.bundle.CompressedEvidenceBundle;
import com.company.aiops.analysis.bundle.EvidenceBundleType;
import com.company.aiops.analysis.bundle.EvidenceCompressor;
import com.company.aiops.analysis.bundle.StoredEvidenceBundle;
import com.company.aiops.analysis.context.AnalysisContext;
import com.company.aiops.analysis.context.AnalysisContextFactory;
import com.company.aiops.analysis.context.AnalysisOptions;
import com.company.aiops.analysis.context.TimeWindow;
import com.company.aiops.analysis.evidence.BaselineEvidence;
import com.company.aiops.analysis.evidence.InterfaceLogEvidence;
import com.company.aiops.analysis.evidence.MetricEvidence;
import com.company.aiops.analysis.evidence.ProviderCollectionResult;
import com.company.aiops.analysis.evidence.RuntimeLogEvidence;
import com.company.aiops.analysis.evidence.TraceEvidence;
import com.company.aiops.analysis.fallback.RuleBasedAnalysisRequest;
import com.company.aiops.analysis.fallback.RuleBasedFallbackAnalyzer;
import com.company.aiops.analysis.job.AnalysisJob;
import com.company.aiops.analysis.job.AnalysisJobStatus;
import com.company.aiops.analysis.orchestrator.state.AnalysisJobStateMachine;
import com.company.aiops.analysis.result.AnalysisMode;
import com.company.aiops.analysis.result.AnalysisResult;
import com.company.aiops.analysis.result.EvidenceReference;
import com.company.aiops.analysis.result.ImpactAssessment;
import com.company.aiops.analysis.result.MissingEvidence;
import com.company.aiops.analysis.result.Recommendation;
import com.company.aiops.analysis.result.RootCauseHypothesis;
import com.company.aiops.analysis.rule.RuleAnalysisService;
import com.company.aiops.analysis.rule.RuleAnalysisTestFixtures;
import com.company.aiops.analysis.rule.model.RuleAnalysisEvidence;
import com.company.aiops.llm.client.LlmCallStatus;
import com.company.aiops.llm.service.LlmAnalysisException;
import com.company.aiops.llm.service.LlmAnalysisService;
import com.company.aiops.persistence.repository.AlertEventRepository;
import com.company.aiops.persistence.repository.EvidenceBundleRepository;
import com.company.aiops.provider.model.ContainerMetrics;
import com.company.aiops.provider.model.CseMetrics;
import com.company.aiops.provider.model.DatabaseMetrics;
import com.company.aiops.provider.model.DcsMetrics;
import com.company.aiops.provider.model.InterfaceBaseline;
import com.company.aiops.provider.model.MqMetrics;
import com.company.aiops.provider.model.ResourceMapping;
import com.company.aiops.provider.orchestrator.ProviderOrchestrator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisJobWorkerTests {

    @Test
    void processTaskPersistsRuleAndCompressedBundlesBeforeCompletingRuleOnlyResult() {
        TestHarness harness = harness(false);
        when(harness.ruleBasedFallbackAnalyzer.analyze(any(RuleBasedAnalysisRequest.class))).thenReturn(harness.ruleOnlyResult);
        when(harness.completionService.complete(anyString(), any())).thenReturn(harness.analysisJob);

        harness.worker.processTask("task-1", AnalysisWorkerScenario.RULE_BASED_ONLY);

        ArgumentCaptor<StoredEvidenceBundle> bundleCaptor = ArgumentCaptor.forClass(StoredEvidenceBundle.class);
        verify(harness.evidenceBundleRepository, org.mockito.Mockito.times(2)).save(bundleCaptor.capture());
        assertThat(bundleCaptor.getAllValues())
                .extracting(StoredEvidenceBundle::evidenceType)
                .containsExactly(EvidenceBundleType.RULE, EvidenceBundleType.COMPRESSED);

        ArgumentCaptor<RuleBasedAnalysisRequest> requestCaptor = ArgumentCaptor.forClass(RuleBasedAnalysisRequest.class);
        verify(harness.ruleBasedFallbackAnalyzer).analyze(requestCaptor.capture());
        RuleBasedAnalysisRequest request = requestCaptor.getValue();
        assertThat(request.analysisMode()).isEqualTo(AnalysisMode.RULE_BASED_ONLY);
        assertThat(request.llmStatus()).isEqualTo("DISABLED");
        assertThat(request.compressedEvidence()).isEqualTo(harness.compressedEvidence);

        ArgumentCaptor<AnalysisResult> resultCaptor = ArgumentCaptor.forClass(AnalysisResult.class);
        verify(harness.completionService).complete(anyString(), resultCaptor.capture());
        assertThat(resultCaptor.getValue()).isEqualTo(harness.ruleOnlyResult);
    }

    @Test
    void processTaskDelegatesToLlmAnalysisServiceWhenConfiguredLlmScenarioIsSelected() {
        TestHarness harness = harness(true);
        when(harness.llmAnalysisService.analyze("task-1", harness.collection, harness.compressedEvidence))
                .thenReturn(harness.llmAssistedResult);
        when(harness.completionService.complete(anyString(), any())).thenReturn(harness.analysisJob);

        harness.worker.processTask("task-1", AnalysisWorkerScenario.USE_CONFIGURED_LLM);

        verify(harness.llmAnalysisService).analyze("task-1", harness.collection, harness.compressedEvidence);
        ArgumentCaptor<AnalysisResult> resultCaptor = ArgumentCaptor.forClass(AnalysisResult.class);
        verify(harness.completionService).complete(anyString(), resultCaptor.capture());
        assertThat(resultCaptor.getValue()).isEqualTo(harness.llmAssistedResult);
    }

    @Test
    void processTaskFallsBackWhenLlmAnalysisFails() {
        TestHarness harness = harness(true);
        when(harness.llmAnalysisService.analyze("task-1", harness.collection, harness.compressedEvidence))
                .thenThrow(new LlmAnalysisException(LlmCallStatus.INVALID_JSON, "response is not valid JSON"));
        when(harness.ruleBasedFallbackAnalyzer.analyze(any(RuleBasedAnalysisRequest.class))).thenReturn(harness.ruleFallbackResult);
        when(harness.completionService.complete(anyString(), any())).thenReturn(harness.analysisJob);

        harness.worker.processTask("task-1", AnalysisWorkerScenario.USE_CONFIGURED_LLM);

        ArgumentCaptor<RuleBasedAnalysisRequest> requestCaptor = ArgumentCaptor.forClass(RuleBasedAnalysisRequest.class);
        verify(harness.ruleBasedFallbackAnalyzer).analyze(requestCaptor.capture());
        RuleBasedAnalysisRequest request = requestCaptor.getValue();
        assertThat(request.analysisMode()).isEqualTo(AnalysisMode.RULE_BASED_DEGRADED);
        assertThat(request.llmStatus()).isEqualTo("INVALID_JSON");
        assertThat(request.degradedReason()).contains("INVALID_JSON");
        verify(harness.completionService).complete("task-1", harness.ruleFallbackResult);
    }

    @Test
    void submitUsesRuleOnlyScenarioWhenLlmIsDisabled() {
        AnalysisJobWorker worker = spy(new AnalysisJobWorker(
                RuleAnalysisTestFixtures.defaultProperties(),
                mock(AnalysisJobStateMachine.class),
                mock(AnalysisJobCompletionService.class),
                mock(AlertEventRepository.class),
                mock(AnalysisContextFactory.class),
                mock(ProviderOrchestrator.class),
                mock(RuleAnalysisService.class),
                mock(EvidenceCompressor.class),
                mock(EvidenceBundleRepository.class),
                mock(RuleBasedFallbackAnalyzer.class),
                mock(LlmAnalysisService.class)
        ));
        doNothing().when(worker).processTask("task-1", AnalysisWorkerScenario.RULE_BASED_ONLY);

        worker.submit("task-1");

        verify(worker).processTask("task-1", AnalysisWorkerScenario.RULE_BASED_ONLY);
    }

    @Test
    void submitUsesConfiguredLlmScenarioWhenLlmIsEnabled() {
        AnalysisJobWorker worker = spy(new AnalysisJobWorker(
                propertiesWithLlmEnabled(true),
                mock(AnalysisJobStateMachine.class),
                mock(AnalysisJobCompletionService.class),
                mock(AlertEventRepository.class),
                mock(AnalysisContextFactory.class),
                mock(ProviderOrchestrator.class),
                mock(RuleAnalysisService.class),
                mock(EvidenceCompressor.class),
                mock(EvidenceBundleRepository.class),
                mock(RuleBasedFallbackAnalyzer.class),
                mock(LlmAnalysisService.class)
        ));
        doNothing().when(worker).processTask("task-2", AnalysisWorkerScenario.USE_CONFIGURED_LLM);

        worker.submit("task-2");

        verify(worker).processTask("task-2", AnalysisWorkerScenario.USE_CONFIGURED_LLM);
    }

    private TestHarness harness(boolean llmEnabled) {
        AnalysisJobStateMachine stateMachine = mock(AnalysisJobStateMachine.class);
        AnalysisJobCompletionService completionService = mock(AnalysisJobCompletionService.class);
        AlertEventRepository alertEventRepository = mock(AlertEventRepository.class);
        AnalysisContextFactory contextFactory = mock(AnalysisContextFactory.class);
        ProviderOrchestrator providerOrchestrator = mock(ProviderOrchestrator.class);
        RuleAnalysisService ruleAnalysisService = mock(RuleAnalysisService.class);
        EvidenceCompressor evidenceCompressor = mock(EvidenceCompressor.class);
        EvidenceBundleRepository evidenceBundleRepository = mock(EvidenceBundleRepository.class);
        RuleBasedFallbackAnalyzer ruleBasedFallbackAnalyzer = mock(RuleBasedFallbackAnalyzer.class);
        LlmAnalysisService llmAnalysisService = mock(LlmAnalysisService.class);

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
                Instant.parse("2026-04-21T02:33:00Z"),
                Instant.parse("2026-04-21T02:33:00Z"),
                new AlertTriggerValues(6.0, 0.8, null, null, null, "demo", "raw")
        );
        AnalysisContext context = new AnalysisContext(
                alert,
                new TimeWindow(alert.metricOccurTime().minus(Duration.ofMinutes(10)), alert.metricOccurTime().plus(Duration.ofMinutes(2))),
                new TimeWindow(alert.metricOccurTime().minus(Duration.ofDays(1)).minus(Duration.ofMinutes(5)), alert.metricOccurTime().minus(Duration.ofDays(1)).plus(Duration.ofMinutes(5))),
                ResourceMapping.empty(alert.serviceName(), alert.cluster()),
                new AnalysisOptions(2000, 20, 20, 50, 500, 10, 10, false, true)
        );
        ProviderCollectionResult collection = new ProviderCollectionResult(
                context,
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
                List.of(new MissingEvidence("runtimeLog", "timeout", false, "runtime log missing")),
                true,
                false,
                "Provider evidence is incomplete: runtimeLog (timeout).",
                List.of()
        );
        RuleAnalysisEvidence ruleAnalysis = RuleAnalysisEvidence.empty();
        CompressedEvidenceBundle compressedEvidence = new CompressedEvidenceBundle(
                new CompressedEvidenceBundle.AlertSnapshot(
                        alert.category(),
                        alert.serviceName(),
                        alert.interfaceName(),
                        alert.cluster(),
                        alert.metricOccurTime(),
                        "alert.request.count",
                        6.0,
                        "alert.fail.rate",
                        0.8,
                        "alert.avg.latency",
                        null,
                        "alert.p99.latency",
                        null
                ),
                new CompressedEvidenceBundle.BaselineSnapshot(true, null, "baseline.request.count", "baseline.fail.rate", "baseline.avg.latency", "baseline.p99.latency", InterfaceBaseline.empty()),
                new CompressedEvidenceBundle.InterfaceLogSnapshot(true, null, "interface.request.count", 6, "interface.failure.rate", 5, 0.8, "interface.latency.profile", 0L, 0L, 0L, 0L, List.of(), List.of(), List.of()),
                new CompressedEvidenceBundle.TraceSnapshot(true, null, List.of(), List.of(), List.of()),
                new CompressedEvidenceBundle.RuntimeLogSnapshot(true, null, List.of()),
                new CompressedEvidenceBundle.MetricSnapshot(true, null, ContainerMetrics.empty(), CseMetrics.empty(), DatabaseMetrics.empty(), DcsMetrics.empty(), MqMetrics.empty(), List.of()),
                ResourceMapping.empty(alert.serviceName(), alert.cluster()),
                new CompressedEvidenceBundle.RuleSnapshot(List.of(), List.of()),
                List.of(new EvidenceReference("alert.request.count", "ALERT", "request count")),
                collection.missingEvidence(),
                List.of("phase-6 note")
        );
        AnalysisResult ruleOnlyResult = new AnalysisResult(
                "task-1",
                alert.alertId(),
                alert.category(),
                AnalysisMode.RULE_BASED_ONLY,
                "Rule-only analysis summary.",
                new ImpactAssessment("LOW", "requestCount=6, failRate=80.0%."),
                List.of(new RootCauseHypothesis(
                        "BUSINESS_ERROR_CODE_NOT_MASKED",
                        "Top error code looks like a business validation failure.",
                        0.55,
                        List.of("error.top.1"),
                        "RULE_ENGINE",
                        "Deterministic rules scored business error semantics highest."
                )),
                List.of(new Recommendation(
                        "P1",
                        "EVALUATE_ERROR_CODE_MASKING",
                        "Review whether the dominant business error code should be masked.",
                        "BIZ_001"
                )),
                compressedEvidence.evidenceRefs(),
                compressedEvidence.missingEvidence(),
                List.of("phase-6 note"),
                null,
                false,
                "DISABLED",
                true,
                true,
                "Provider evidence is incomplete: runtimeLog (timeout).",
                "fallback-rules-v1",
                0.48,
                Instant.now()
        );
        AnalysisResult llmAssistedResult = new AnalysisResult(
                "task-1",
                alert.alertId(),
                alert.category(),
                AnalysisMode.LLM_ASSISTED,
                "LLM-assisted analysis summary.",
                new ImpactAssessment("LOW", "LLM-assisted impact assessment."),
                List.of(new RootCauseHypothesis(
                        "BUSINESS_ERROR_CODE_NOT_MASKED",
                        "LLM concluded business validation failure is most likely.",
                        0.77,
                        List.of("alert.request.count"),
                        "MERGED",
                        "The validated LLM output reused compressed evidence ids."
                )),
                List.of(new Recommendation(
                        "P1",
                        "CHECK_LOG",
                        "Inspect representative business validation errors.",
                        alert.interfaceName()
                )),
                compressedEvidence.evidenceRefs(),
                compressedEvidence.missingEvidence(),
                List.of("phase-6 note"),
                null,
                true,
                "SUCCESS",
                true,
                true,
                "Provider evidence is incomplete: runtimeLog (timeout).",
                null,
                0.72,
                Instant.now()
        );
        AnalysisResult ruleFallbackResult = new AnalysisResult(
                "task-1",
                alert.alertId(),
                alert.category(),
                AnalysisMode.RULE_BASED_DEGRADED,
                "Fallback analysis summary.",
                new ImpactAssessment("LOW", "Fallback impact assessment."),
                List.of(new RootCauseHypothesis(
                        "BUSINESS_ERROR_CODE_NOT_MASKED",
                        "Fallback rules concluded business validation failure is most likely.",
                        0.60,
                        List.of("alert.request.count"),
                        "RULE_ENGINE",
                        "LLM output was invalid, so the deterministic fallback path was used."
                )),
                List.of(new Recommendation(
                        "P1",
                        "CHECK_LOG",
                        "Inspect representative business validation errors.",
                        alert.interfaceName()
                )),
                compressedEvidence.evidenceRefs(),
                compressedEvidence.missingEvidence(),
                List.of("phase-6 note"),
                null,
                false,
                "INVALID_JSON",
                true,
                true,
                "Provider evidence is incomplete: runtimeLog (timeout). LLM analysis failed with status INVALID_JSON. Switched to deterministic fallback rules.",
                "fallback-rules-v1",
                0.62,
                Instant.now()
        );
        AnalysisJob analysisJob = new AnalysisJob(
                "task-1",
                alert.alertId(),
                alert.category(),
                "key",
                AnalysisJobStatus.PENDING,
                null,
                null,
                0,
                0,
                Instant.now(),
                null,
                null,
                null
        );

        when(stateMachine.transition(anyString(), any(), anyString(), anyString())).thenReturn(analysisJob);
        when(alertEventRepository.findByAlertId(alert.alertId())).thenReturn(Optional.of(alert));
        when(contextFactory.create(alert)).thenReturn(context);
        when(providerOrchestrator.collect("task-1", context)).thenReturn(collection);
        when(ruleAnalysisService.analyze(collection)).thenReturn(ruleAnalysis);
        when(evidenceCompressor.compress(collection, ruleAnalysis)).thenReturn(compressedEvidence);

        AnalysisJobWorker worker = new AnalysisJobWorker(
                propertiesWithLlmEnabled(llmEnabled),
                stateMachine,
                completionService,
                alertEventRepository,
                contextFactory,
                providerOrchestrator,
                ruleAnalysisService,
                evidenceCompressor,
                evidenceBundleRepository,
                ruleBasedFallbackAnalyzer,
                llmAnalysisService
        );
        return new TestHarness(
                worker,
                stateMachine,
                completionService,
                ruleBasedFallbackAnalyzer,
                llmAnalysisService,
                evidenceBundleRepository,
                analysisJob,
                collection,
                compressedEvidence,
                ruleOnlyResult,
                llmAssistedResult,
                ruleFallbackResult
        );
    }

    private com.company.aiops.common.config.AiopsProperties propertiesWithLlmEnabled(boolean enabled) {
        return new com.company.aiops.common.config.AiopsProperties(
                RuleAnalysisTestFixtures.defaultProperties().analysis(),
                RuleAnalysisTestFixtures.defaultProperties().provider(),
                new com.company.aiops.common.config.AiopsProperties.Llm(
                        enabled,
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

    private record TestHarness(
            AnalysisJobWorker worker,
            AnalysisJobStateMachine stateMachine,
            AnalysisJobCompletionService completionService,
            RuleBasedFallbackAnalyzer ruleBasedFallbackAnalyzer,
            LlmAnalysisService llmAnalysisService,
            EvidenceBundleRepository evidenceBundleRepository,
            AnalysisJob analysisJob,
            ProviderCollectionResult collection,
            CompressedEvidenceBundle compressedEvidence,
            AnalysisResult ruleOnlyResult,
            AnalysisResult llmAssistedResult,
            AnalysisResult ruleFallbackResult
    ) {
    }
}
