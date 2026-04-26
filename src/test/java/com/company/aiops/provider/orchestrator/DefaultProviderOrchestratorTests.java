package com.company.aiops.provider.orchestrator;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.alert.model.AlertEvent;
import com.company.aiops.alert.model.AlertTriggerValues;
import com.company.aiops.analysis.context.AnalysisContext;
import com.company.aiops.analysis.context.AnalysisOptions;
import com.company.aiops.analysis.context.TimeWindow;
import com.company.aiops.analysis.evidence.ProviderCollectionResult;
import com.company.aiops.common.config.AiopsProperties;
import com.company.aiops.persistence.repository.ProviderCallLogRepository;
import com.company.aiops.provider.baseline.BaselineProvider;
import com.company.aiops.provider.log.InterfaceLogProvider;
import com.company.aiops.provider.log.RuntimeLogProvider;
import com.company.aiops.provider.metric.MetricProvider;
import com.company.aiops.provider.model.ContainerMetrics;
import com.company.aiops.provider.model.CseMetrics;
import com.company.aiops.provider.model.DatabaseMetrics;
import com.company.aiops.provider.model.DcsMetrics;
import com.company.aiops.provider.model.InterfaceBaseline;
import com.company.aiops.provider.model.InterfaceLogEntry;
import com.company.aiops.provider.model.MqMetrics;
import com.company.aiops.provider.model.ProviderCallLog;
import com.company.aiops.provider.model.ProviderStatus;
import com.company.aiops.provider.model.ResourceMapping;
import com.company.aiops.provider.model.ResourceMappingItem;
import com.company.aiops.provider.model.RuntimeLogEntry;
import com.company.aiops.provider.resource.ResourceMappingProvider;
import com.company.aiops.provider.trace.TraceProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultProviderOrchestratorTests {

    private ThreadPoolTaskExecutor providerExecutor;
    private ThreadPoolTaskExecutor providerOrchestratorExecutor;

    @AfterEach
    void tearDown() {
        if (providerExecutor != null) {
            providerExecutor.shutdown();
        }
        if (providerOrchestratorExecutor != null) {
            providerOrchestratorExecutor.shutdown();
        }
    }

    @Test
    void collectStartsIndependentProvidersInParallel() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        InMemoryProviderCallLogRepository callLogRepository = new InMemoryProviderCallLogRepository();
        DefaultProviderOrchestrator orchestrator = orchestrator(
                properties(true),
                callLogRepository,
                (serviceName, cluster) -> new ResourceMapping(
                        serviceName,
                        cluster,
                        List.of(new ResourceMappingItem("DATABASE", "db-1", "db-1", null))
                ),
                (serviceName, interfaceName, cluster, occurTime, compareDuration, baselineOffsetDays) -> {
                    started.countDown();
                    await(release);
                    return InterfaceBaseline.empty();
                },
                (serviceName, interfaceName, window, limit) -> {
                    started.countDown();
                    await(release);
                    return List.of(
                            new InterfaceLogEntry(serviceName, interfaceName, "trace-1", "1", null, "uid-1", false, "E1", "msg", 120L, window.endTime()),
                            new InterfaceLogEntry(serviceName, interfaceName, "trace-2", "2", null, "uid-2", false, "E1", "msg", 110L, window.endTime())
                    );
                },
                traceId -> List.of(new InterfaceLogEntry("trace-service", "trace.if", traceId, "span", null, "uid", true, null, null, 200L, Instant.now())),
                (traceId, serviceName, window, limit) -> List.of(new RuntimeLogEntry(serviceName, traceId, "WARN", "runtime sample", Instant.now())),
                new StableMetricProvider()
        );

        CompletableFuture<ProviderCollectionResult> future = CompletableFuture.supplyAsync(() ->
                orchestrator.collect("task-parallel", context(AlertCategory.RPC_ERROR_RATE))
        );

        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        release.countDown();

        ProviderCollectionResult result = future.get(2, TimeUnit.SECONDS);
        assertThat(result.ruleFallbackRequired()).isFalse();
        assertThat(result.degradedByProviderFailure()).isFalse();
        assertThat(result.interfaceLogEvidence().sampledTraceIds()).containsExactly("trace-1", "trace-2");
        assertThat(result.traceEvidence().collected()).isTrue();
        assertThat(callLogRepository.logs)
                .extracting(ProviderCallLog::providerName)
                .contains("baseline", "interfaceLog", "metric.container", "trace", "runtimeLog");
    }

    @Test
    void collectMarksNonCriticalProviderFailureAsDegradedWithoutFallback() {
        DefaultProviderOrchestrator orchestrator = orchestrator(
                properties(true),
                new InMemoryProviderCallLogRepository(),
                (serviceName, cluster) -> ResourceMapping.empty(serviceName, cluster),
                (serviceName, interfaceName, cluster, occurTime, compareDuration, baselineOffsetDays) -> InterfaceBaseline.empty(),
                (serviceName, interfaceName, window, limit) -> List.of(
                        new InterfaceLogEntry(serviceName, interfaceName, "trace-1", "1", null, "uid-1", false, "E1", "msg", 120L, window.endTime())
                ),
                traceId -> List.of(new InterfaceLogEntry("trace-service", "trace.if", traceId, "span", null, "uid", true, null, null, 200L, Instant.now())),
                (traceId, serviceName, window, limit) -> {
                    throw new IllegalStateException("runtime provider unavailable");
                },
                new StableMetricProvider()
        );

        ProviderCollectionResult result = orchestrator.collect("task-runtime-fail", context(AlertCategory.RPC_ERROR_RATE));

        assertThat(result.ruleFallbackRequired()).isFalse();
        assertThat(result.degradedByProviderFailure()).isTrue();
        assertThat(result.missingEvidence())
                .extracting("providerName")
                .contains("runtimeLog");
    }

    @Test
    void collectRequiresFallbackWhenInterfaceLogsFail() {
        DefaultProviderOrchestrator orchestrator = orchestrator(
                properties(true),
                new InMemoryProviderCallLogRepository(),
                (serviceName, cluster) -> ResourceMapping.empty(serviceName, cluster),
                (serviceName, interfaceName, cluster, occurTime, compareDuration, baselineOffsetDays) -> InterfaceBaseline.empty(),
                (serviceName, interfaceName, window, limit) -> {
                    throw new IllegalStateException("interface log provider unavailable");
                },
                traceId -> List.of(),
                (traceId, serviceName, window, limit) -> List.of(),
                new StableMetricProvider()
        );

        ProviderCollectionResult result = orchestrator.collect("task-interface-fail", context(AlertCategory.RPC_ERROR_RATE));

        assertThat(result.ruleFallbackRequired()).isTrue();
        assertThat(result.degradedByProviderFailure()).isTrue();
        assertThat(result.degradedReason()).contains("Interface logs are unavailable");
        assertThat(result.missingEvidence())
                .extracting("providerName")
                .contains("interfaceLog");
        assertThat(result.missingEvidence())
                .extracting("critical")
                .contains(true);
    }

    private DefaultProviderOrchestrator orchestrator(
            AiopsProperties properties,
            ProviderCallLogRepository providerCallLogRepository,
            ResourceMappingProvider resourceMappingProvider,
            BaselineProvider baselineProvider,
            InterfaceLogProvider interfaceLogProvider,
            TraceProvider traceProvider,
            RuntimeLogProvider runtimeLogProvider,
            MetricProvider metricProvider
    ) {
        providerExecutor = new ThreadPoolTaskExecutor();
        providerExecutor.setCorePoolSize(8);
        providerExecutor.setMaxPoolSize(8);
        providerExecutor.setQueueCapacity(32);
        providerExecutor.setThreadNamePrefix("test-provider-");
        providerExecutor.initialize();
        providerOrchestratorExecutor = new ThreadPoolTaskExecutor();
        providerOrchestratorExecutor.setCorePoolSize(8);
        providerOrchestratorExecutor.setMaxPoolSize(8);
        providerOrchestratorExecutor.setQueueCapacity(32);
        providerOrchestratorExecutor.setThreadNamePrefix("test-provider-orchestrator-");
        providerOrchestratorExecutor.initialize();
        ProviderCallExecutor providerCallExecutor = new ProviderCallExecutor(properties, providerCallLogRepository, providerExecutor);
        return new DefaultProviderOrchestrator(
                properties,
                providerCallExecutor,
                resourceMappingProvider,
                baselineProvider,
                interfaceLogProvider,
                traceProvider,
                runtimeLogProvider,
                metricProvider,
                providerOrchestratorExecutor
        );
    }

    private AnalysisContext context(AlertCategory category) {
        Instant occurTime = Instant.parse("2026-04-21T02:33:00Z");
        AlertEvent alert = new AlertEvent(
                "alert-" + category.name(),
                category,
                "OPEN",
                "MAJOR",
                "demo-service",
                "demo.Interface.query",
                "cluster1",
                "cn_product_default",
                "tenant",
                "application",
                "demo alert",
                category == AlertCategory.RPC_LATENCY ? "rpc_latency" : "rpc_error_rate",
                category == AlertCategory.RPC_LATENCY ? "AIOps@delay" : "AIOps@rate",
                "raw alert",
                occurTime,
                occurTime,
                new AlertTriggerValues(20.0, 1.0, 3000L, null, null, "demo", "raw")
        );
        return new AnalysisContext(
                alert,
                new TimeWindow(occurTime.minus(Duration.ofMinutes(5)), occurTime.plus(Duration.ofMinutes(2))),
                new TimeWindow(occurTime.minus(Duration.ofDays(1)).minus(Duration.ofMinutes(5)), occurTime.minus(Duration.ofDays(1)).plus(Duration.ofMinutes(5))),
                ResourceMapping.empty(alert.serviceName(), alert.cluster()),
                new AnalysisOptions(2000, 20, 20, 50, 500, 10, 10, false, true)
        );
    }

    private AiopsProperties properties(boolean parallelEnabled) {
        return new AiopsProperties(
                new AiopsProperties.Analysis(
                        Duration.ofMinutes(5),
                        Duration.ofDays(1),
                        30,
                        30,
                        Duration.ofMinutes(3),
                        10,
                        1,
                        Duration.ofMinutes(10),
                        2000,
                        20,
                        20,
                        50,
                        500,
                        10,
                        10,
                        5
                ),
                new AiopsProperties.Provider(
                        Duration.ofSeconds(1),
                        0,
                        Duration.ofMillis(10),
                        parallelEnabled,
                        true
                ),
                new AiopsProperties.Llm(
                        false,
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
                new AiopsProperties.Thresholds(0.8, 0.85, 0.8, 0.8, 0.8, 0.8, 0.85)
        );
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static final class InMemoryProviderCallLogRepository implements ProviderCallLogRepository {

        private final List<ProviderCallLog> logs = new CopyOnWriteArrayList<>();

        @Override
        public void save(ProviderCallLog providerCallLog) {
            logs.add(providerCallLog);
        }

        @Override
        public List<ProviderCallLog> findByTaskId(String taskId) {
            return logs.stream().filter(log -> log.taskId().equals(taskId)).toList();
        }
    }

    private static final class StableMetricProvider implements MetricProvider {

        @Override
        public ContainerMetrics getContainerMetrics(String serviceName, String cluster, TimeWindow window) {
            return ContainerMetrics.empty();
        }

        @Override
        public DatabaseMetrics getDatabaseMetrics(List<String> dbInstanceIds, TimeWindow window) {
            return DatabaseMetrics.empty();
        }

        @Override
        public DcsMetrics getDcsMetrics(List<String> dcsInstanceIds, TimeWindow window) {
            return DcsMetrics.empty();
        }

        @Override
        public MqMetrics getMqMetrics(List<String> mqInstanceIds, TimeWindow window) {
            return MqMetrics.empty();
        }

        @Override
        public CseMetrics getCseMetrics(String serviceName, String cluster, TimeWindow window) {
            return CseMetrics.empty();
        }
    }
}
