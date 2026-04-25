package com.company.aiops.analysis.orchestrator.worker;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.alert.model.AlertEvent;
import com.company.aiops.alert.model.AlertTriggerValues;
import com.company.aiops.analysis.bundle.CompressedEvidenceBundle;
import com.company.aiops.analysis.bundle.EvidenceCompressor;
import com.company.aiops.analysis.bundle.EvidenceBundleType;
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
import com.company.aiops.analysis.job.AnalysisJob;
import com.company.aiops.analysis.job.AnalysisJobStatus;
import com.company.aiops.analysis.orchestrator.state.AnalysisJobStateMachine;
import com.company.aiops.analysis.result.AnalysisMode;
import com.company.aiops.analysis.result.AnalysisResult;
import com.company.aiops.analysis.result.EvidenceReference;
import com.company.aiops.analysis.result.MissingEvidence;
import com.company.aiops.analysis.rule.RuleAnalysisService;
import com.company.aiops.analysis.rule.RuleAnalysisTestFixtures;
import com.company.aiops.analysis.rule.model.RuleAnalysisEvidence;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisJobWorkerTests {

    @Test
    void processTaskPersistsRuleAndCompressedBundlesBeforeCompleting() {
        AnalysisJobStateMachine stateMachine = mock(AnalysisJobStateMachine.class);
        AnalysisJobCompletionService completionService = mock(AnalysisJobCompletionService.class);
        AlertEventRepository alertEventRepository = mock(AlertEventRepository.class);
        AnalysisContextFactory contextFactory = mock(AnalysisContextFactory.class);
        ProviderOrchestrator providerOrchestrator = mock(ProviderOrchestrator.class);
        RuleAnalysisService ruleAnalysisService = mock(RuleAnalysisService.class);
        EvidenceCompressor evidenceCompressor = mock(EvidenceCompressor.class);
        EvidenceBundleRepository evidenceBundleRepository = mock(EvidenceBundleRepository.class);

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
                null,
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
        when(completionService.complete(anyString(), any())).thenReturn(analysisJob);

        AnalysisJobWorker worker = new AnalysisJobWorker(
                RuleAnalysisTestFixtures.defaultProperties(),
                stateMachine,
                completionService,
                alertEventRepository,
                contextFactory,
                providerOrchestrator,
                ruleAnalysisService,
                evidenceCompressor,
                evidenceBundleRepository
        );

        worker.processTask("task-1", AnalysisWorkerScenario.RULE_BASED_ONLY);

        ArgumentCaptor<StoredEvidenceBundle> bundleCaptor = ArgumentCaptor.forClass(StoredEvidenceBundle.class);
        verify(evidenceBundleRepository, org.mockito.Mockito.times(2)).save(bundleCaptor.capture());
        assertThat(bundleCaptor.getAllValues())
                .extracting(StoredEvidenceBundle::evidenceType)
                .containsExactly(EvidenceBundleType.RULE, EvidenceBundleType.COMPRESSED);

        ArgumentCaptor<AnalysisResult> resultCaptor = ArgumentCaptor.forClass(AnalysisResult.class);
        verify(completionService).complete(anyString(), resultCaptor.capture());
        AnalysisResult result = resultCaptor.getValue();
        assertThat(result.analysisMode()).isEqualTo(AnalysisMode.RULE_BASED_ONLY);
        assertThat(result.evidenceRefs()).containsExactlyElementsOf(compressedEvidence.evidenceRefs());
        assertThat(result.missingEvidence()).containsExactlyElementsOf(compressedEvidence.missingEvidence());
        assertThat(result.insufficientEvidence()).contains("phase-6 note");
    }
}
