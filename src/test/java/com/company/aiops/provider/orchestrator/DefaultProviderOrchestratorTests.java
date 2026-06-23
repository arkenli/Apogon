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
import com.company.aiops.provider.ProviderNames;
import com.company.aiops.provider.baseline.BaselineProvider;
import com.company.aiops.provider.log.InterfaceLogProvider;
import com.company.aiops.provider.log.RuntimeLogProvider;
import com.company.aiops.provider.metric.ContainerMetricProvider;
import com.company.aiops.provider.metric.CseMetricProvider;
import com.company.aiops.provider.metric.DatabaseMetricProvider;
import com.company.aiops.provider.metric.DcsMetricProvider;
import com.company.aiops.provider.metric.MqMetricProvider;
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
import com.company.aiops.provider.query.ProviderQueryTarget;
import com.company.aiops.provider.query.ProviderQueryTargetResolver;
import com.company.aiops.provider.query.ProviderQueryTargets;
import com.company.aiops.provider.resource.ResourceMappingProvider;
import com.company.aiops.provider.trace.TraceProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
                fallbackResolver(),
                (serviceName, cluster) -> new ResourceMapping(
                        serviceName,
                        cluster,
                        List.of(new ResourceMappingItem("DATABASE", "db-1", "db-1", null))
                ),
                (serviceName, interfaceName, cluster, baselineWindow) -> {
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
                runtimeLogProvider((traceId, serviceName, window, limit) -> List.of(
                        runtimeLogEntry(serviceName, traceId, "WARN", "runtime sample", Instant.now())
                )),
                new StableContainerMetricProvider(),
                new StableCseMetricProvider(),
                new StableDatabaseMetricProvider(),
                new StableDcsMetricProvider(),
                new StableMqMetricProvider()
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
                fallbackResolver(),
                (serviceName, cluster) -> ResourceMapping.empty(serviceName, cluster),
                (serviceName, interfaceName, cluster, baselineWindow) -> InterfaceBaseline.empty(),
                (serviceName, interfaceName, window, limit) -> List.of(
                        new InterfaceLogEntry(serviceName, interfaceName, "trace-1", "1", null, "uid-1", false, "E1", "msg", 120L, window.endTime())
                ),
                traceId -> List.of(new InterfaceLogEntry("trace-service", "trace.if", traceId, "span", null, "uid", true, null, null, 200L, Instant.now())),
                runtimeLogProvider((traceId, serviceName, window, limit) -> {
                    throw new IllegalStateException("runtime provider unavailable");
                }),
                new StableContainerMetricProvider(),
                new StableCseMetricProvider(),
                new StableDatabaseMetricProvider(),
                new StableDcsMetricProvider(),
                new StableMqMetricProvider()
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
                fallbackResolver(),
                (serviceName, cluster) -> ResourceMapping.empty(serviceName, cluster),
                (serviceName, interfaceName, cluster, baselineWindow) -> InterfaceBaseline.empty(),
                (serviceName, interfaceName, window, limit) -> {
                    throw new IllegalStateException("interface log provider unavailable");
                },
                traceId -> List.of(),
                runtimeLogProvider((traceId, serviceName, window, limit) -> List.of()),
                new StableContainerMetricProvider(),
                new StableCseMetricProvider(),
                new StableDatabaseMetricProvider(),
                new StableDcsMetricProvider(),
                new StableMqMetricProvider()
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

    @Test
    void collectUsesProviderSpecificQueryTargetsForServiceLevelProviders() {
        CapturingResourceMappingProvider resourceMappingProvider = new CapturingResourceMappingProvider();
        CapturingBaselineProvider baselineProvider = new CapturingBaselineProvider();
        CapturingContainerMetricProvider containerMetricProvider = new CapturingContainerMetricProvider();
        CapturingCseMetricProvider cseMetricProvider = new CapturingCseMetricProvider();
        DefaultProviderOrchestrator orchestrator = orchestrator(
                properties(true),
                new InMemoryProviderCallLogRepository(),
                (serviceName, cluster, providerNames) -> new ProviderQueryTargets(
                        serviceName,
                        cluster,
                        Map.of(
                                ProviderNames.BASELINE,
                                ProviderQueryTarget.mapped(
                                        ProviderNames.BASELINE,
                                        "demo-service-baseline",
                                        "cluster2_base"
                                ),
                                ProviderNames.METRIC_CONTAINER,
                                ProviderQueryTarget.mapped(
                                        ProviderNames.METRIC_CONTAINER,
                                        "demo-service-container",
                                        "cluster2_front"
                                ),
                                ProviderNames.METRIC_CSE,
                                ProviderQueryTarget.mapped(
                                        ProviderNames.METRIC_CSE,
                                        "demo-service-cse",
                                        "cluster2_rpc"
                                )
                        )
                ),
                resourceMappingProvider,
                baselineProvider,
                (serviceName, interfaceName, window, limit) -> List.of(),
                traceId -> List.of(),
                runtimeLogProvider((traceId, serviceName, window, limit) -> List.of()),
                containerMetricProvider,
                cseMetricProvider,
                new StableDatabaseMetricProvider(),
                new StableDcsMetricProvider(),
                new StableMqMetricProvider()
        );

        ProviderCollectionResult result = orchestrator.collect("task-query-targets", context(AlertCategory.RPC_ERROR_RATE));

        assertThat(result.ruleFallbackRequired()).isFalse();
        assertThat(baselineProvider.serviceName).isEqualTo("demo-service-baseline");
        assertThat(baselineProvider.clusterName).isEqualTo("cluster2_base");
        assertThat(containerMetricProvider.serviceName).isEqualTo("demo-service-container");
        assertThat(containerMetricProvider.clusterName).isEqualTo("cluster2_front");
        assertThat(cseMetricProvider.serviceName).isEqualTo("demo-service-cse");
        assertThat(cseMetricProvider.clusterName).isEqualTo("cluster2_rpc");
        assertThat(resourceMappingProvider.serviceName).isEqualTo("demo-service");
        assertThat(resourceMappingProvider.clusterName).isEqualTo("cluster1");
    }

    @Test
    void collectContinuesWithAlertClusterWhenQueryMappingResolutionFails() {
        InMemoryProviderCallLogRepository callLogRepository = new InMemoryProviderCallLogRepository();
        CapturingContainerMetricProvider containerMetricProvider = new CapturingContainerMetricProvider();
        DefaultProviderOrchestrator orchestrator = orchestrator(
                properties(true),
                callLogRepository,
                (serviceName, cluster, providerNames) -> {
                    throw new IllegalStateException("mapping table unavailable");
                },
                (serviceName, cluster) -> ResourceMapping.empty(serviceName, cluster),
                (serviceName, interfaceName, cluster, baselineWindow) -> InterfaceBaseline.empty(),
                (serviceName, interfaceName, window, limit) -> List.of(
                        new InterfaceLogEntry(serviceName, interfaceName, "trace-1", "1", null, "uid-1", false, "E1", "msg", 120L, window.endTime())
                ),
                traceId -> List.of(),
                runtimeLogProvider((traceId, serviceName, window, limit) -> List.of()),
                containerMetricProvider,
                new StableCseMetricProvider(),
                new StableDatabaseMetricProvider(),
                new StableDcsMetricProvider(),
                new StableMqMetricProvider()
        );

        ProviderCollectionResult result = orchestrator.collect("task-query-targets-fail", context(AlertCategory.RPC_ERROR_RATE));

        assertThat(result.ruleFallbackRequired()).isFalse();
        assertThat(result.degradedByProviderFailure()).isTrue();
        assertThat(containerMetricProvider.clusterName).isEqualTo("cluster1");
        assertThat(result.missingEvidence())
                .extracting("providerName")
                .contains(ProviderNames.PROVIDER_QUERY_MAPPING);
        assertThat(callLogRepository.logs)
                .extracting(ProviderCallLog::providerName)
                .contains(ProviderNames.PROVIDER_QUERY_MAPPING, ProviderNames.METRIC_CONTAINER);
        assertThat(result.providerResults())
                .extracting("providerName")
                .containsSubsequence(ProviderNames.PROVIDER_QUERY_MAPPING, ProviderNames.RESOURCE_MAPPING);
    }

    @Test
    void collectStartsInterfaceLogEvenWhenQueryMappingResolutionIsBlocked() throws Exception {
        CountDownLatch queryMappingStarted = new CountDownLatch(1);
        CountDownLatch releaseQueryMapping = new CountDownLatch(1);
        CountDownLatch interfaceLogStarted = new CountDownLatch(1);
        DefaultProviderOrchestrator orchestrator = orchestrator(
                properties(true),
                new InMemoryProviderCallLogRepository(),
                (serviceName, cluster, providerNames) -> {
                    queryMappingStarted.countDown();
                    await(releaseQueryMapping);
                    return ProviderQueryTargets.fallback(serviceName, cluster, providerNames);
                },
                (serviceName, cluster) -> ResourceMapping.empty(serviceName, cluster),
                (serviceName, interfaceName, cluster, baselineWindow) -> InterfaceBaseline.empty(),
                (serviceName, interfaceName, window, limit) -> {
                    interfaceLogStarted.countDown();
                    return List.of();
                },
                traceId -> List.of(),
                runtimeLogProvider((traceId, serviceName, window, limit) -> List.of()),
                new StableContainerMetricProvider(),
                new StableCseMetricProvider(),
                new StableDatabaseMetricProvider(),
                new StableDcsMetricProvider(),
                new StableMqMetricProvider()
        );

        CompletableFuture<ProviderCollectionResult> future = CompletableFuture.supplyAsync(() ->
                orchestrator.collect("task-query-mapping-blocked", context(AlertCategory.RPC_ERROR_RATE))
        );

        assertThat(queryMappingStarted.await(1, TimeUnit.SECONDS)).isTrue();
        boolean interfaceLogObserved;
        try {
            interfaceLogObserved = interfaceLogStarted.await(300, TimeUnit.MILLISECONDS);
        } finally {
            releaseQueryMapping.countDown();
        }

        ProviderCollectionResult result = future.get(2, TimeUnit.SECONDS);

        assertThat(interfaceLogObserved).isTrue();
        assertThat(result.ruleFallbackRequired()).isFalse();
    }

    @Test
    void collectIgnoresQueryMappingFailureForFallbackWhenContinueOnNonCriticalFailureIsFalse() {
        DefaultProviderOrchestrator orchestrator = orchestrator(
                properties(true, false),
                new InMemoryProviderCallLogRepository(),
                (serviceName, cluster, providerNames) -> {
                    throw new IllegalStateException("mapping table unavailable");
                },
                (serviceName, cluster) -> ResourceMapping.empty(serviceName, cluster),
                (serviceName, interfaceName, cluster, baselineWindow) -> InterfaceBaseline.empty(),
                (serviceName, interfaceName, window, limit) -> List.of(),
                traceId -> List.of(),
                runtimeLogProvider((traceId, serviceName, window, limit) -> List.of()),
                new StableContainerMetricProvider(),
                new StableCseMetricProvider(),
                new StableDatabaseMetricProvider(),
                new StableDcsMetricProvider(),
                new StableMqMetricProvider()
        );

        ProviderCollectionResult result = orchestrator.collect(
                "task-query-mapping-no-fallback",
                context(AlertCategory.RPC_ERROR_RATE)
        );

        assertThat(result.ruleFallbackRequired()).isFalse();
        assertThat(result.degradedByProviderFailure()).isTrue();
        assertThat(result.missingEvidence())
                .extracting("providerName")
                .containsExactly(ProviderNames.PROVIDER_QUERY_MAPPING);
    }

    @Test
    void collectPrioritizesOperationalMissingEvidenceAheadOfQueryMappingFailure() {
        DefaultProviderOrchestrator orchestrator = orchestrator(
                properties(true),
                new InMemoryProviderCallLogRepository(),
                (serviceName, cluster, providerNames) -> {
                    throw new IllegalStateException("mapping table unavailable");
                },
                (serviceName, cluster) -> ResourceMapping.empty(serviceName, cluster),
                (serviceName, interfaceName, cluster, baselineWindow) -> InterfaceBaseline.empty(),
                (serviceName, interfaceName, window, limit) -> List.of(
                        new InterfaceLogEntry(serviceName, interfaceName, "trace-1", "1", null, "uid-1", false, "E1", "msg", 120L, window.endTime())
                ),
                traceId -> List.of(new InterfaceLogEntry("trace-service", "trace.if", traceId, "span", null, "uid", true, null, null, 200L, Instant.now())),
                runtimeLogProvider((traceId, serviceName, window, limit) -> {
                    throw new IllegalStateException("runtime provider unavailable");
                }),
                new StableContainerMetricProvider(),
                new StableCseMetricProvider(),
                new StableDatabaseMetricProvider(),
                new StableDcsMetricProvider(),
                new StableMqMetricProvider()
        );

        ProviderCollectionResult result = orchestrator.collect(
                "task-query-mapping-priority",
                context(AlertCategory.RPC_ERROR_RATE)
        );

        assertThat(result.missingEvidence())
                .extracting("providerName")
                .containsExactly(ProviderNames.RUNTIME_LOG, ProviderNames.PROVIDER_QUERY_MAPPING);
    }

    @Test
    void collectServiceErrorLogCutsMessageQueryUntilRuntimeLogHit() {
        CapturingRuntimeLogProvider runtimeLogProvider = new CapturingRuntimeLogProvider(true);
        DefaultProviderOrchestrator orchestrator = orchestrator(
                properties(true),
                new InMemoryProviderCallLogRepository(),
                fallbackResolver(),
                (serviceName, cluster) -> ResourceMapping.empty(serviceName, cluster),
                (serviceName, interfaceName, cluster, baselineWindow) -> InterfaceBaseline.empty(),
                (serviceName, interfaceName, window, limit) -> List.of(),
                traceId -> List.of(new InterfaceLogEntry("trace-service", "trace.if", traceId, "span", null, "uid", true, null, null, 200L, Instant.now())),
                runtimeLogProvider,
                new StableContainerMetricProvider(),
                new StableCseMetricProvider(),
                new StableDatabaseMetricProvider(),
                new StableDcsMetricProvider(),
                new StableMqMetricProvider()
        );

        ProviderCollectionResult result = orchestrator.collect("task-service-log-cut", serviceErrorLogContext());

        assertThat(runtimeLogProvider.messageQueries)
                .containsExactly("prebook loginAppointment failed", "loginAppointment failed");
        assertThat(runtimeLogProvider.levels).containsOnly("ERROR");
        assertThat(result.ruleFallbackRequired()).isFalse();
        assertThat(result.runtimeLogEvidence().messageQueryMatched()).isTrue();
        assertThat(result.runtimeLogEvidence().matchedLogQuery()).isEqualTo("loginAppointment failed");
        assertThat(result.runtimeLogEvidence().sampledTraceIds()).containsExactly("trace-log-1", "trace-log-2");
        assertThat(result.traceEvidence().collected()).isTrue();
        assertThat(result.interfaceLogEvidence().collected()).isFalse();
    }

    @Test
    void collectServiceErrorLogRequiresFallbackWhenAllMessageQueriesMiss() {
        CapturingRuntimeLogProvider runtimeLogProvider = new CapturingRuntimeLogProvider(false);
        DefaultProviderOrchestrator orchestrator = orchestrator(
                properties(true),
                new InMemoryProviderCallLogRepository(),
                fallbackResolver(),
                (serviceName, cluster) -> ResourceMapping.empty(serviceName, cluster),
                (serviceName, interfaceName, cluster, baselineWindow) -> InterfaceBaseline.empty(),
                (serviceName, interfaceName, window, limit) -> List.of(),
                traceId -> List.of(),
                runtimeLogProvider,
                new StableContainerMetricProvider(),
                new StableCseMetricProvider(),
                new StableDatabaseMetricProvider(),
                new StableDcsMetricProvider(),
                new StableMqMetricProvider()
        );

        ProviderCollectionResult result = orchestrator.collect("task-service-log-miss", serviceErrorLogContext());

        assertThat(result.ruleFallbackRequired()).isTrue();
        assertThat(result.degradedReason()).contains("ERROR runtime log context");
        assertThat(result.runtimeLogEvidence().collected()).isFalse();
        assertThat(result.runtimeLogEvidence().messageQueryMatched()).isFalse();
        assertThat(result.runtimeLogEvidence().matchedLogQuery()).isNull();
        assertThat(result.runtimeLogEvidence().queryAttempts())
                .extracting("query")
                .containsExactly("prebook loginAppointment failed", "loginAppointment failed");
        assertThat(result.runtimeLogEvidence().queryAttempts())
                .allSatisfy(attempt -> {
                    assertThat(attempt.level()).isEqualTo("ERROR");
                    assertThat(attempt.hitCount()).isZero();
                    assertThat(attempt.selected()).isFalse();
                });
        assertThat(result.missingEvidence())
                .anySatisfy(missingEvidence -> {
                    assertThat(missingEvidence.providerName()).isEqualTo(ProviderNames.RUNTIME_LOG);
                    assertThat(missingEvidence.critical()).isTrue();
                });
    }

    @Test
    void collectServiceErrorLogStopsMessageQueriesWhenRuntimeProviderFails() {
        CapturingRuntimeLogProvider runtimeLogProvider = new CapturingRuntimeLogProvider(true, true);
        DefaultProviderOrchestrator orchestrator = orchestrator(
                properties(true),
                new InMemoryProviderCallLogRepository(),
                fallbackResolver(),
                (serviceName, cluster) -> ResourceMapping.empty(serviceName, cluster),
                (serviceName, interfaceName, cluster, baselineWindow) -> InterfaceBaseline.empty(),
                (serviceName, interfaceName, window, limit) -> List.of(),
                traceId -> List.of(),
                runtimeLogProvider,
                new StableContainerMetricProvider(),
                new StableCseMetricProvider(),
                new StableDatabaseMetricProvider(),
                new StableDcsMetricProvider(),
                new StableMqMetricProvider()
        );

        ProviderCollectionResult result = orchestrator.collect("task-service-log-provider-fail", serviceErrorLogContext());

        assertThat(runtimeLogProvider.messageQueries).containsExactly("prebook loginAppointment failed");
        assertThat(result.ruleFallbackRequired()).isTrue();
        assertThat(result.runtimeLogEvidence().collected()).isFalse();
        assertThat(result.missingEvidence())
                .anySatisfy(missingEvidence -> {
                    assertThat(missingEvidence.providerName()).isEqualTo(ProviderNames.RUNTIME_LOG);
                    assertThat(missingEvidence.critical()).isTrue();
                });
    }

    private DefaultProviderOrchestrator orchestrator(
            AiopsProperties properties,
            ProviderCallLogRepository providerCallLogRepository,
            ProviderQueryTargetResolver providerQueryTargetResolver,
            ResourceMappingProvider resourceMappingProvider,
            BaselineProvider baselineProvider,
            InterfaceLogProvider interfaceLogProvider,
            TraceProvider traceProvider,
            RuntimeLogProvider runtimeLogProvider,
            ContainerMetricProvider containerMetricProvider,
            CseMetricProvider cseMetricProvider,
            DatabaseMetricProvider databaseMetricProvider,
            DcsMetricProvider dcsMetricProvider,
            MqMetricProvider mqMetricProvider
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
                providerQueryTargetResolver,
                resourceMappingProvider,
                baselineProvider,
                interfaceLogProvider,
                traceProvider,
                runtimeLogProvider,
                containerMetricProvider,
                cseMetricProvider,
                databaseMetricProvider,
                dcsMetricProvider,
                mqMetricProvider,
                providerOrchestratorExecutor
        );
    }

    private AnalysisContext serviceErrorLogContext() {
        Instant occurTime = Instant.parse("2026-04-21T02:33:00Z");
        AlertEvent alert = new AlertEvent(
                "alert-SERVICE_ERROR_LOG",
                AlertCategory.SERVICE_ERROR_LOG,
                "OPEN",
                "MAJOR",
                "ams_front",
                null,
                "cluster2",
                "cn_product_default",
                "tenant",
                "application",
                "service error log",
                "service_error_log",
                "AIOps@serviceLog",
                "raw alert",
                occurTime,
                occurTime,
                new AlertTriggerValues(null, null, null, null, null, 2.0, "ERROR", "prebook loginAppointment failed", "prebook loginAppointment failed", "demo", "raw")
        );
        return new AnalysisContext(
                alert,
                new TimeWindow(occurTime.minus(Duration.ofMinutes(5)), occurTime.plus(Duration.ofMinutes(2))),
                new TimeWindow(occurTime.minus(Duration.ofDays(1)).minus(Duration.ofMinutes(5)), occurTime.minus(Duration.ofDays(1)).plus(Duration.ofMinutes(5))),
                ResourceMapping.empty(alert.serviceName(), alert.cluster()),
                new AnalysisOptions(2000, 20, 20, 50, 500, 10, 10, 5, 8, 2, "ERROR", false, true)
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
                new AlertTriggerValues(20.0, 1.0, 3000L, null, null, null, null, null, null, "demo", "raw")
        );
        return new AnalysisContext(
                alert,
                new TimeWindow(occurTime.minus(Duration.ofMinutes(5)), occurTime.plus(Duration.ofMinutes(2))),
                new TimeWindow(occurTime.minus(Duration.ofDays(1)).minus(Duration.ofMinutes(5)), occurTime.minus(Duration.ofDays(1)).plus(Duration.ofMinutes(5))),
                ResourceMapping.empty(alert.serviceName(), alert.cluster()),
                new AnalysisOptions(2000, 20, 20, 50, 500, 10, 10, 5, 8, 2, "ERROR", false, true)
        );
    }

    private AiopsProperties properties(boolean parallelEnabled) {
        return properties(parallelEnabled, true);
    }

    private AiopsProperties properties(boolean parallelEnabled, boolean continueOnNonCriticalFailure) {
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
                        5,
                        8,
                        2,
                        "ERROR",
                        5
                ),
                new AiopsProperties.Provider(
                        Duration.ofSeconds(1),
                        0,
                        Duration.ofMillis(10),
                        parallelEnabled,
                        continueOnNonCriticalFailure
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

    private ProviderQueryTargetResolver fallbackResolver() {
        return (serviceName, cluster, providerNames) -> ProviderQueryTargets.fallback(
                serviceName,
                cluster,
                providerNames == null ? Set.of() : providerNames
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

    private RuntimeLogProvider runtimeLogProvider(RuntimeLogQuery query) {
        return new RuntimeLogProvider() {
            @Override
            public List<RuntimeLogEntry> queryWarnAndErrorLogs(
                    String traceId,
                    String serviceName,
                    TimeWindow window,
                    int limit
            ) {
                return query.queryWarnAndErrorLogs(traceId, serviceName, window, limit);
            }

            @Override
            public List<RuntimeLogEntry> queryByMessage(
                    String serviceName,
                    String cluster,
                    String level,
                    String messageQuery,
                    TimeWindow window,
                    int limit
            ) {
                return List.of();
            }
        };
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

    @FunctionalInterface
    private interface RuntimeLogQuery {

        List<RuntimeLogEntry> queryWarnAndErrorLogs(String traceId, String serviceName, TimeWindow window, int limit);
    }

    private static final class CapturingRuntimeLogProvider implements RuntimeLogProvider {

        private final boolean hitCutMessage;
        private final boolean failFirstMessageQuery;
        private final List<String> messageQueries = new CopyOnWriteArrayList<>();
        private final List<String> levels = new CopyOnWriteArrayList<>();

        private CapturingRuntimeLogProvider(boolean hitCutMessage) {
            this(hitCutMessage, false);
        }

        private CapturingRuntimeLogProvider(boolean hitCutMessage, boolean failFirstMessageQuery) {
            this.hitCutMessage = hitCutMessage;
            this.failFirstMessageQuery = failFirstMessageQuery;
        }

        @Override
        public List<RuntimeLogEntry> queryWarnAndErrorLogs(
                String traceId,
                String serviceName,
                TimeWindow window,
                int limit
        ) {
            return List.of();
        }

        @Override
        public List<RuntimeLogEntry> queryByMessage(
                String serviceName,
                String cluster,
                String level,
                String messageQuery,
                TimeWindow window,
                int limit
        ) {
            messageQueries.add(messageQuery);
            levels.add(level);
            if (failFirstMessageQuery && messageQueries.size() == 1) {
                throw new IllegalStateException("runtime message provider unavailable");
            }
            if (!hitCutMessage || !"loginAppointment failed".equals(messageQuery)) {
                return List.of();
            }
            return List.of(
                    new RuntimeLogEntry(
                            serviceName,
                            cluster,
                            null,
                            "trace-log-1",
                            null,
                            level,
                            null,
                            null,
                            "prebook loginAppointment failed: trace-log-1",
                            null,
                            null,
                            window.endTime()
                    ),
                    new RuntimeLogEntry(
                            serviceName,
                            cluster,
                            null,
                            "trace-log-2",
                            null,
                            level,
                            null,
                            null,
                            "prebook loginAppointment failed: trace-log-2",
                            null,
                            null,
                            window.endTime()
                    )
            );
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

    private static final class StableContainerMetricProvider implements ContainerMetricProvider {

        @Override
        public ContainerMetrics getContainerMetrics(String serviceName, String cluster, TimeWindow window) {
            return ContainerMetrics.empty();
        }
    }

    private static final class StableDatabaseMetricProvider implements DatabaseMetricProvider {

        @Override
        public DatabaseMetrics getDatabaseMetrics(List<String> dbInstanceIds, TimeWindow window) {
            return DatabaseMetrics.empty();
        }
    }

    private static final class StableDcsMetricProvider implements DcsMetricProvider {

        @Override
        public DcsMetrics getDcsMetrics(List<String> dcsInstanceIds, TimeWindow window) {
            return DcsMetrics.empty();
        }
    }

    private static final class StableMqMetricProvider implements MqMetricProvider {

        @Override
        public MqMetrics getMqMetrics(List<String> mqInstanceIds, TimeWindow window) {
            return MqMetrics.empty();
        }
    }

    private static final class StableCseMetricProvider implements CseMetricProvider {

        @Override
        public CseMetrics getCseMetrics(String serviceName, String cluster, TimeWindow window) {
            return CseMetrics.empty();
        }
    }

    private static final class CapturingResourceMappingProvider implements ResourceMappingProvider {

        private String serviceName;
        private String clusterName;

        @Override
        public ResourceMapping queryResourceMapping(String serviceName, String cluster) {
            this.serviceName = serviceName;
            this.clusterName = cluster;
            return ResourceMapping.empty(serviceName, cluster);
        }
    }

    private static final class CapturingBaselineProvider implements BaselineProvider {

        private String serviceName;
        private String clusterName;

        @Override
        public InterfaceBaseline getInterfaceBaseline(
                String serviceName,
                String interfaceName,
                String cluster,
                TimeWindow baselineWindow
        ) {
            this.serviceName = serviceName;
            this.clusterName = cluster;
            return InterfaceBaseline.empty();
        }
    }

    private static final class CapturingContainerMetricProvider implements ContainerMetricProvider {

        private String serviceName;
        private String clusterName;

        @Override
        public ContainerMetrics getContainerMetrics(String serviceName, String cluster, TimeWindow window) {
            this.serviceName = serviceName;
            this.clusterName = cluster;
            return ContainerMetrics.empty();
        }
    }

    private static final class CapturingCseMetricProvider implements CseMetricProvider {

        private String serviceName;
        private String clusterName;

        @Override
        public CseMetrics getCseMetrics(String serviceName, String cluster, TimeWindow window) {
            this.serviceName = serviceName;
            this.clusterName = cluster;
            return CseMetrics.empty();
        }
    }
}
