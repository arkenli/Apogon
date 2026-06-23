package com.company.aiops.provider.orchestrator;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.analysis.context.AnalysisContext;
import com.company.aiops.analysis.evidence.BaselineEvidence;
import com.company.aiops.analysis.evidence.InterfaceLogEvidence;
import com.company.aiops.analysis.evidence.MetricEvidence;
import com.company.aiops.analysis.evidence.ProviderCollectionResult;
import com.company.aiops.analysis.evidence.RuntimeLogEvidence;
import com.company.aiops.analysis.evidence.TraceEvidence;
import com.company.aiops.analysis.evidence.TraceRecord;
import com.company.aiops.analysis.result.MissingEvidence;
import com.company.aiops.common.config.AiopsProperties;
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
import com.company.aiops.provider.model.ProviderResult;
import com.company.aiops.provider.model.ProviderStatus;
import com.company.aiops.provider.model.ResourceMapping;
import com.company.aiops.provider.model.RuntimeLogEntry;
import com.company.aiops.provider.query.ProviderQueryTarget;
import com.company.aiops.provider.query.ProviderQueryTargetResolver;
import com.company.aiops.provider.query.ProviderQueryTargets;
import com.company.aiops.provider.resource.ResourceMappingProvider;
import com.company.aiops.provider.trace.TraceProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Default provider collection flow from the design document. Independent providers are scheduled in
 * parallel, while trace/runtime-log calls wait for sampled trace ids from interface logs.
 */
@Service
public class DefaultProviderOrchestrator implements ProviderOrchestrator {

    private static final String PROVIDER_RESOURCE_MAPPING = ProviderNames.RESOURCE_MAPPING;
    private static final String PROVIDER_BASELINE = ProviderNames.BASELINE;
    private static final String PROVIDER_INTERFACE_LOG = ProviderNames.INTERFACE_LOG;
    private static final String PROVIDER_TRACE = ProviderNames.TRACE;
    private static final String PROVIDER_RUNTIME_LOG = ProviderNames.RUNTIME_LOG;
    private static final String PROVIDER_METRIC_CONTAINER = ProviderNames.METRIC_CONTAINER;
    private static final String PROVIDER_METRIC_CSE = ProviderNames.METRIC_CSE;
    private static final String PROVIDER_METRIC_DATABASE = ProviderNames.METRIC_DATABASE;
    private static final String PROVIDER_METRIC_DCS = ProviderNames.METRIC_DCS;
    private static final String PROVIDER_METRIC_MQ = ProviderNames.METRIC_MQ;
    private static final String PROVIDER_QUERY_MAPPING = ProviderNames.PROVIDER_QUERY_MAPPING;
    private static final Set<String> QUERY_TARGET_PROVIDER_NAMES = ProviderNames.queryMappableProviderNames();

    private final AiopsProperties aiopsProperties;
    private final ProviderCallExecutor providerCallExecutor;
    private final ProviderQueryTargetResolver providerQueryTargetResolver;
    private final ResourceMappingProvider resourceMappingProvider;
    private final BaselineProvider baselineProvider;
    private final InterfaceLogProvider interfaceLogProvider;
    private final TraceProvider traceProvider;
    private final RuntimeLogProvider runtimeLogProvider;
    private final ContainerMetricProvider containerMetricProvider;
    private final CseMetricProvider cseMetricProvider;
    private final DatabaseMetricProvider databaseMetricProvider;
    private final DcsMetricProvider dcsMetricProvider;
    private final MqMetricProvider mqMetricProvider;
    private final Executor providerOrchestratorExecutor;

    public DefaultProviderOrchestrator(
            AiopsProperties aiopsProperties,
            ProviderCallExecutor providerCallExecutor,
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
            MqMetricProvider mqMetricProvider,
            @Qualifier("providerOrchestratorExecutor") Executor providerOrchestratorExecutor
    ) {
        this.aiopsProperties = aiopsProperties;
        this.providerCallExecutor = providerCallExecutor;
        this.providerQueryTargetResolver = providerQueryTargetResolver;
        this.resourceMappingProvider = resourceMappingProvider;
        this.baselineProvider = baselineProvider;
        this.interfaceLogProvider = interfaceLogProvider;
        this.traceProvider = traceProvider;
        this.runtimeLogProvider = runtimeLogProvider;
        this.containerMetricProvider = containerMetricProvider;
        this.cseMetricProvider = cseMetricProvider;
        this.databaseMetricProvider = databaseMetricProvider;
        this.dcsMetricProvider = dcsMetricProvider;
        this.mqMetricProvider = mqMetricProvider;
        this.providerOrchestratorExecutor = providerOrchestratorExecutor;
    }

    @Override
    public ProviderCollectionResult collect(String taskId, AnalysisContext initialContext) {
        if (initialContext.alert().category() == AlertCategory.SERVICE_ERROR_LOG) {
            return collectServiceErrorLog(taskId, initialContext);
        }
        return collectRpcAlert(taskId, initialContext);
    }

    private ProviderCollectionResult collectRpcAlert(String taskId, AnalysisContext initialContext) {
        CommonProviderResults common = collectCommonProviders(taskId, initialContext, true);
        AnalysisContext context = common.context();
        List<String> sampledTraceIds = sampleTraceIds(context.alert().category(), common.interfaceLogResult(), context);

        ProviderResult<List<TraceRecord>> traceResult = collectTraces(taskId, context, sampledTraceIds);
        ProviderResult<List<RuntimeLogEntry>> runtimeLogResult = collectRuntimeLogs(taskId, context, sampledTraceIds);

        BaselineEvidence baselineEvidence = toBaselineEvidence(common.baselineResult());
        InterfaceLogEvidence interfaceLogEvidence = toInterfaceLogEvidence(common.interfaceLogResult(), sampledTraceIds);
        TraceEvidence traceEvidence = toTraceEvidence(traceResult, sampledTraceIds);
        RuntimeLogEvidence runtimeLogEvidence = toRuntimeLogEvidence(runtimeLogResult, sampledTraceIds);
        MetricEvidence metricEvidence = metricEvidence(common.metricAggregateResult());

        List<ProviderResult<?>> providerResults = providerResults(common, traceResult, runtimeLogResult);
        List<MissingEvidence> missingEvidence = buildMissingEvidence(context.alert().category(), providerResults);

        boolean interfaceLogUnavailable = common.interfaceLogResult().isFailure();
        boolean latencyTraceUnavailable = context.alert().category() == AlertCategory.RPC_LATENCY
                && !sampledTraceIds.isEmpty()
                && traceResult.isFailure();
        boolean nonCriticalFailure = providerResults.stream()
                .filter(result -> !Objects.equals(result.providerName(), PROVIDER_INTERFACE_LOG))
                .filter(result -> !Objects.equals(result.providerName(), PROVIDER_QUERY_MAPPING))
                .anyMatch(this::hasMissingEvidence);
        boolean fallbackRequired = interfaceLogUnavailable
                || latencyTraceUnavailable
                || (!aiopsProperties.provider().continueOnNonCriticalFailure() && nonCriticalFailure);
        boolean degradedByProviderFailure = !missingEvidence.isEmpty();
        String degradedReason = buildDegradedReason(
                interfaceLogUnavailable,
                latencyTraceUnavailable,
                nonCriticalFailure,
                missingEvidence
        );

        return new ProviderCollectionResult(
                context,
                baselineEvidence,
                interfaceLogEvidence,
                traceEvidence,
                runtimeLogEvidence,
                metricEvidence,
                missingEvidence,
                degradedByProviderFailure,
                fallbackRequired,
                degradedReason,
                providerResults
        );
    }

    private ProviderCollectionResult collectServiceErrorLog(String taskId, AnalysisContext initialContext) {
        CommonProviderResults common = collectCommonProviders(taskId, initialContext, false);
        AnalysisContext context = common.context();

        RuntimeLogMessageLookup runtimeLogLookup = collectRuntimeLogsByMessage(taskId, context);
        List<String> sampledTraceIds = runtimeLogLookup.sampledTraceIds();
        ProviderResult<List<TraceRecord>> traceResult = collectTraces(
                taskId,
                context,
                sampledTraceIds,
                "runtime logs"
        );

        BaselineEvidence baselineEvidence = toBaselineEvidence(common.baselineResult());
        InterfaceLogEvidence interfaceLogEvidence = toInterfaceLogEvidence(common.interfaceLogResult(), List.of());
        TraceEvidence traceEvidence = toTraceEvidence(traceResult, sampledTraceIds, "runtime logs");
        RuntimeLogEvidence runtimeLogEvidence = runtimeLogLookup.evidence();
        MetricEvidence metricEvidence = metricEvidence(common.metricAggregateResult());

        List<ProviderResult<?>> providerResults = providerResults(common, traceResult, runtimeLogLookup.result());
        List<MissingEvidence> missingEvidence = buildMissingEvidence(context.alert().category(), providerResults);

        boolean runtimeLogUnavailable = runtimeLogLookup.result().isFailure();
        boolean nonCriticalFailure = providerResults.stream()
                .filter(result -> !Objects.equals(result.providerName(), PROVIDER_INTERFACE_LOG))
                .filter(result -> !Objects.equals(result.providerName(), PROVIDER_QUERY_MAPPING))
                .filter(result -> !Objects.equals(result.providerName(), PROVIDER_RUNTIME_LOG))
                .anyMatch(this::hasMissingEvidence);
        boolean fallbackRequired = runtimeLogUnavailable
                || (!aiopsProperties.provider().continueOnNonCriticalFailure() && nonCriticalFailure);
        boolean degradedByProviderFailure = !missingEvidence.isEmpty();
        String degradedReason = buildServiceErrorLogDegradedReason(
                runtimeLogUnavailable,
                nonCriticalFailure,
                missingEvidence
        );

        return new ProviderCollectionResult(
                context,
                baselineEvidence,
                interfaceLogEvidence,
                traceEvidence,
                runtimeLogEvidence,
                metricEvidence,
                missingEvidence,
                degradedByProviderFailure,
                fallbackRequired,
                degradedReason,
                providerResults
        );
    }

    private CommonProviderResults collectCommonProviders(
            String taskId,
            AnalysisContext initialContext,
            boolean collectInterfaceLogs
    ) {
        CompletableFuture<ProviderResult<ResourceMapping>> resourceMappingFuture = schedule(() ->
                execute(taskId, initialContext, PROVIDER_RESOURCE_MAPPING, false, () ->
                        resourceMappingProvider.queryResourceMapping(
                                initialContext.alert().serviceName(),
                                initialContext.alert().cluster()
                        )));
        CompletableFuture<ProviderResult<List<InterfaceLogEntry>>> interfaceLogFuture = schedule(() -> {
            if (!collectInterfaceLogs) {
                return providerCallExecutor.skipped(
                        taskId,
                        initialContext.alert().alertId(),
                        PROVIDER_INTERFACE_LOG,
                        false,
                        "SERVICE_ERROR_LOG alerts do not include interfaceName; interface-log lookup skipped"
                );
            }
            return execute(taskId, initialContext, PROVIDER_INTERFACE_LOG, true, () ->
                        interfaceLogProvider.queryByInterface(
                                initialContext.alert().serviceName(),
                                initialContext.alert().interfaceName(),
                                initialContext.alarmWindow(),
                                initialContext.options().maxInterfaceLogLimit()
                        ));
        });
        ProviderResult<ProviderQueryTargets> providerQueryTargetsResult = execute(
                taskId,
                initialContext,
                PROVIDER_QUERY_MAPPING,
                false,
                () -> providerQueryTargetResolver.resolveAll(
                        initialContext.alert().serviceName(),
                        initialContext.alert().cluster(),
                        QUERY_TARGET_PROVIDER_NAMES
                )
        );
        ProviderQueryTargets providerQueryTargets = providerQueryTargetsResult.isUsable()
                && providerQueryTargetsResult.data() != null
                ? providerQueryTargetsResult.data()
                : ProviderQueryTargets.fallback(
                        initialContext.alert().serviceName(),
                        initialContext.alert().cluster(),
                        QUERY_TARGET_PROVIDER_NAMES
                );
        ProviderQueryTarget baselineTarget = providerQueryTargets.targetFor(PROVIDER_BASELINE);
        ProviderQueryTarget containerTarget = providerQueryTargets.targetFor(PROVIDER_METRIC_CONTAINER);
        ProviderQueryTarget cseTarget = providerQueryTargets.targetFor(PROVIDER_METRIC_CSE);
        CompletableFuture<ProviderResult<InterfaceBaseline>> baselineFuture = schedule(() ->
                execute(taskId, initialContext, PROVIDER_BASELINE, false, () ->
                        baselineProvider.getInterfaceBaseline(
                                baselineTarget.serviceName(),
                                initialContext.alert().interfaceName(),
                                baselineTarget.clusterName(),
                                initialContext.baselineWindow()
                        )));
        CompletableFuture<ProviderResult<ContainerMetrics>> containerMetricFuture = schedule(() ->
                execute(taskId, initialContext, PROVIDER_METRIC_CONTAINER, false, () ->
                        containerMetricProvider.getContainerMetrics(
                                containerTarget.serviceName(),
                                containerTarget.clusterName(),
                                initialContext.alarmWindow()
                        )));
        CompletableFuture<ProviderResult<CseMetrics>> cseMetricFuture = schedule(() ->
                execute(taskId, initialContext, PROVIDER_METRIC_CSE, false, () ->
                        cseMetricProvider.getCseMetrics(
                                cseTarget.serviceName(),
                                cseTarget.clusterName(),
                                initialContext.alarmWindow()
                        )));

        ProviderResult<ResourceMapping> resourceMappingResult = join(resourceMappingFuture);
        ResourceMapping resourceMapping = resourceMappingResult.isUsable() && resourceMappingResult.data() != null
                ? resourceMappingResult.data()
                : ResourceMapping.empty(initialContext.alert().serviceName(), initialContext.alert().cluster());
        AnalysisContext context = initialContext.withResourceMapping(resourceMapping);

        CompletableFuture<ProviderResult<DatabaseMetrics>> databaseMetricFuture = schedule(() ->
                resourceMapping.resourceIds("DATABASE").isEmpty()
                        ? providerCallExecutor.skipped(
                        taskId,
                        context.alert().alertId(),
                        PROVIDER_METRIC_DATABASE,
                        false,
                        "no DATABASE resource mapping resolved"
                )
                        : execute(taskId, context, PROVIDER_METRIC_DATABASE, false, () ->
                        databaseMetricProvider.getDatabaseMetrics(resourceMapping.resourceIds("DATABASE"), context.alarmWindow())));
        CompletableFuture<ProviderResult<DcsMetrics>> dcsMetricFuture = schedule(() ->
                resourceMapping.resourceIds("DCS").isEmpty()
                        ? providerCallExecutor.skipped(
                        taskId,
                        context.alert().alertId(),
                        PROVIDER_METRIC_DCS,
                        false,
                        "no DCS resource mapping resolved"
                )
                        : execute(taskId, context, PROVIDER_METRIC_DCS, false, () ->
                        dcsMetricProvider.getDcsMetrics(resourceMapping.resourceIds("DCS"), context.alarmWindow())));
        CompletableFuture<ProviderResult<MqMetrics>> mqMetricFuture = schedule(() ->
                resourceMapping.resourceIds("MQ").isEmpty()
                        ? providerCallExecutor.skipped(
                        taskId,
                        context.alert().alertId(),
                        PROVIDER_METRIC_MQ,
                        false,
                        "no MQ resource mapping resolved"
                )
                        : execute(taskId, context, PROVIDER_METRIC_MQ, false, () ->
                        mqMetricProvider.getMqMetrics(resourceMapping.resourceIds("MQ"), context.alarmWindow())));

        ProviderResult<InterfaceBaseline> baselineResult = join(baselineFuture);
        ProviderResult<List<InterfaceLogEntry>> interfaceLogResult = join(interfaceLogFuture);
        ProviderResult<ContainerMetrics> containerMetricResult = join(containerMetricFuture);
        ProviderResult<CseMetrics> cseMetricResult = join(cseMetricFuture);
        ProviderResult<DatabaseMetrics> databaseMetricResult = join(databaseMetricFuture);
        ProviderResult<DcsMetrics> dcsMetricResult = join(dcsMetricFuture);
        ProviderResult<MqMetrics> mqMetricResult = join(mqMetricFuture);

        ProviderResult<MetricEvidence> metricAggregateResult = aggregateMetricResults(
                containerMetricResult,
                cseMetricResult,
                databaseMetricResult,
                dcsMetricResult,
                mqMetricResult
        );

        return new CommonProviderResults(
                context,
                providerQueryTargetsResult,
                resourceMappingResult,
                baselineResult,
                interfaceLogResult,
                containerMetricResult,
                cseMetricResult,
                databaseMetricResult,
                dcsMetricResult,
                mqMetricResult,
                metricAggregateResult
        );
    }

    private List<ProviderResult<?>> providerResults(
            CommonProviderResults common,
            ProviderResult<List<TraceRecord>> traceResult,
            ProviderResult<List<RuntimeLogEntry>> runtimeLogResult
    ) {
        return List.of(
                common.providerQueryTargetsResult(),
                common.resourceMappingResult(),
                common.baselineResult(),
                common.interfaceLogResult(),
                common.containerMetricResult(),
                common.cseMetricResult(),
                common.databaseMetricResult(),
                common.dcsMetricResult(),
                common.mqMetricResult(),
                traceResult,
                runtimeLogResult
        );
    }

    private MetricEvidence metricEvidence(ProviderResult<MetricEvidence> metricAggregateResult) {
        return metricAggregateResult.data() == null
                ? MetricEvidence.missing(metricAggregateResult.errorMessage())
                : metricAggregateResult.data();
    }

    private record CommonProviderResults(
            AnalysisContext context,
            ProviderResult<ProviderQueryTargets> providerQueryTargetsResult,
            ProviderResult<ResourceMapping> resourceMappingResult,
            ProviderResult<InterfaceBaseline> baselineResult,
            ProviderResult<List<InterfaceLogEntry>> interfaceLogResult,
            ProviderResult<ContainerMetrics> containerMetricResult,
            ProviderResult<CseMetrics> cseMetricResult,
            ProviderResult<DatabaseMetrics> databaseMetricResult,
            ProviderResult<DcsMetrics> dcsMetricResult,
            ProviderResult<MqMetrics> mqMetricResult,
            ProviderResult<MetricEvidence> metricAggregateResult
    ) {
    }

    private record RuntimeLogMessageLookup(
            ProviderResult<List<RuntimeLogEntry>> result,
            RuntimeLogEvidence evidence,
            List<String> sampledTraceIds
    ) {
    }

    private <T> CompletableFuture<ProviderResult<T>> schedule(Supplier<ProviderResult<T>> supplier) {
        if (!aiopsProperties.provider().parallelEnabled()) {
            return CompletableFuture.completedFuture(supplier.get());
        }
        return CompletableFuture.supplyAsync(supplier, providerOrchestratorExecutor);
    }

    private <T> ProviderResult<T> execute(
            String taskId,
            AnalysisContext context,
            String providerName,
            boolean critical,
            Callable<T> operation
    ) {
        return providerCallExecutor.execute(
                taskId,
                context.alert().alertId(),
                providerName,
                critical,
                operation
        );
    }

    private <T> ProviderResult<T> join(CompletableFuture<ProviderResult<T>> future) {
        return future.join();
    }

    private List<String> sampleTraceIds(
            AlertCategory category,
            ProviderResult<List<InterfaceLogEntry>> interfaceLogResult,
            AnalysisContext context
    ) {
        if (!interfaceLogResult.isUsable() || interfaceLogResult.data() == null || interfaceLogResult.data().isEmpty()) {
            return List.of();
        }

        return switch (category) {
            case RPC_ERROR_RATE -> interfaceLogResult.data().stream()
                    .filter(entry -> !entry.success())
                    .map(InterfaceLogEntry::traceId)
                    .filter(this::hasText)
                    .collect(Collectors.collectingAndThen(
                            Collectors.toCollection(LinkedHashSet::new),
                            traceIds -> traceIds.stream()
                                    .limit(context.options().maxTraceSampleSize())
                                    .toList()
                    ));
            case RPC_LATENCY -> interfaceLogResult.data().stream()
                    .filter(entry -> hasText(entry.traceId()))
                    .sorted(Comparator.comparingLong(InterfaceLogEntry::latencyMs).reversed())
                    .map(InterfaceLogEntry::traceId)
                    .collect(Collectors.collectingAndThen(
                            Collectors.toCollection(LinkedHashSet::new),
                            traceIds -> traceIds.stream()
                                    .limit(context.options().maxSlowTraceSampleSize())
                                    .toList()
                    ));
            case SERVICE_ERROR_LOG, UNKNOWN -> List.of();
        };
    }

    private ProviderResult<List<TraceRecord>> collectTraces(
            String taskId,
            AnalysisContext context,
            List<String> sampledTraceIds
    ) {
        return collectTraces(taskId, context, sampledTraceIds, "interface logs");
    }

    private ProviderResult<List<TraceRecord>> collectTraces(
            String taskId,
            AnalysisContext context,
            List<String> sampledTraceIds,
            String traceIdSource
    ) {
        if (sampledTraceIds.isEmpty()) {
            return providerCallExecutor.skipped(
                    taskId,
                    context.alert().alertId(),
                    PROVIDER_TRACE,
                    context.alert().category() == AlertCategory.RPC_LATENCY,
                    "no sampled trace ids available from " + traceIdSource
                );
        }

        List<CompletableFuture<ProviderResult<TraceRecord>>> futures = sampledTraceIds.stream()
                .map(traceId -> schedule(() -> providerCallExecutor.execute(
                        taskId,
                        context.alert().alertId(),
                        PROVIDER_TRACE,
                        context.alert().category() == AlertCategory.RPC_LATENCY,
                        () -> new TraceRecord(traceId, traceProvider.queryTraceInterfaces(traceId))
                )))
                .toList();

        List<ProviderResult<TraceRecord>> results = futures.stream().map(this::join).toList();
        List<TraceRecord> traces = results.stream()
                .filter(ProviderResult::isUsable)
                .map(ProviderResult::data)
                .filter(Objects::nonNull)
                .toList();
        return aggregateResults(PROVIDER_TRACE, traces, results, "trace queries skipped");
    }

    private ProviderResult<List<RuntimeLogEntry>> collectRuntimeLogs(
            String taskId,
            AnalysisContext context,
            List<String> sampledTraceIds
    ) {
        if (sampledTraceIds.isEmpty()) {
            return providerCallExecutor.skipped(
                    taskId,
                    context.alert().alertId(),
                    PROVIDER_RUNTIME_LOG,
                    false,
                    "no sampled trace ids available for runtime-log lookup"
            );
        }

        List<CompletableFuture<ProviderResult<List<RuntimeLogEntry>>>> futures = sampledTraceIds.stream()
                .map(traceId -> schedule(() -> providerCallExecutor.execute(
                        taskId,
                        context.alert().alertId(),
                        PROVIDER_RUNTIME_LOG,
                        false,
                        () -> runtimeLogProvider.queryWarnAndErrorLogs(
                                traceId,
                                context.alert().serviceName(),
                                context.alarmWindow(),
                                context.options().maxRuntimeLogSize()
                        )
                )))
                .toList();

        List<ProviderResult<List<RuntimeLogEntry>>> results = futures.stream().map(this::join).toList();
        List<RuntimeLogEntry> mergedEntries = results.stream()
                .filter(ProviderResult::isUsable)
                .map(ProviderResult::data)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .limit(context.options().maxRuntimeLogSize())
                .toList();
        return aggregateResults(PROVIDER_RUNTIME_LOG, mergedEntries, results, "runtime-log queries skipped");
    }

    private RuntimeLogMessageLookup collectRuntimeLogsByMessage(String taskId, AnalysisContext context) {
        String level = defaultIfBlank(context.options().errorLogQueryLevel(), "ERROR");
        List<String> candidates = runtimeLogMessageQueryCandidates(context);
        List<RuntimeLogEvidence.QueryAttempt> queryAttempts = new ArrayList<>();
        List<ProviderResult<List<RuntimeLogEntry>>> attemptResults = new ArrayList<>();

        for (int index = 0; index < candidates.size(); index++) {
            String candidate = candidates.get(index);
            ProviderResult<List<RuntimeLogEntry>> attemptResult = execute(
                    taskId,
                    context,
                    PROVIDER_RUNTIME_LOG,
                    true,
                    () -> runtimeLogProvider.queryByMessage(
                            context.alert().serviceName(),
                            context.alert().cluster(),
                            level,
                            candidate,
                            context.alarmWindow(),
                            context.options().maxRuntimeLogSize()
                    )
            );
            attemptResults.add(attemptResult);
            List<RuntimeLogEntry> entries = attemptResult.isUsable() && attemptResult.data() != null
                    ? attemptResult.data().stream().limit(context.options().maxRuntimeLogSize()).toList()
                    : List.of();
            boolean selected = !entries.isEmpty();
            queryAttempts.add(new RuntimeLogEvidence.QueryAttempt(
                    "runtime.query.attempt." + (index + 1),
                    candidate,
                    level,
                    entries.size(),
                    selected
            ));
            if (attemptResult.isFailure()) {
                String reason = defaultIfBlank(
                        attemptResult.errorMessage(),
                        "runtime log message query failed"
                );
                return new RuntimeLogMessageLookup(
                        attemptResult,
                        missingRuntimeLogEvidence(reason, queryAttempts),
                        List.of()
                );
            }
            if (selected) {
                List<String> sampledTraceIds = sampleTraceIdsFromRuntimeLogs(entries, context);
                ProviderResult<List<RuntimeLogEntry>> result = ProviderResult.success(
                        PROVIDER_RUNTIME_LOG,
                        entries,
                        sumLatency(attemptResults)
                );
                RuntimeLogEvidence evidence = RuntimeLogEvidence.availableMessageQuery(
                        entries,
                        candidate,
                        queryAttempts,
                        sampledTraceIds
                );
                return new RuntimeLogMessageLookup(result, evidence, sampledTraceIds);
            }
        }

        String reason = "Could not locate " + level + " runtime log context from alert message queries.";
        ProviderResult<List<RuntimeLogEntry>> result = ProviderResult.failure(
                PROVIDER_RUNTIME_LOG,
                "RUNTIME_LOG_CONTEXT_MISSING",
                reason,
                sumLatency(attemptResults)
        );
        return new RuntimeLogMessageLookup(result, missingRuntimeLogEvidence(reason, queryAttempts), List.of());
    }

    private RuntimeLogEvidence missingRuntimeLogEvidence(
            String reason,
            List<RuntimeLogEvidence.QueryAttempt> queryAttempts
    ) {
        return new RuntimeLogEvidence(List.of(), false, reason, false, null, queryAttempts, List.of());
    }

    private List<String> runtimeLogMessageQueryCandidates(AnalysisContext context) {
        if (context.alert().triggerValues() == null
                || !hasText(context.alert().triggerValues().normalizedLogQuery())) {
            return List.of();
        }
        String[] tokens = context.alert().triggerValues().normalizedLogQuery().trim().split("\\s+");
        List<String> rawCandidates = new ArrayList<>();
        rawCandidates.add(joinTokens(tokens, 0, tokens.length));
        if (tokens.length > 1) {
            rawCandidates.add(joinTokens(tokens, 1, tokens.length));
        }
        if (tokens.length > 2) {
            rawCandidates.add(joinTokens(tokens, 2, tokens.length));
        }
        if (tokens.length >= 2) {
            rawCandidates.add(joinTokens(tokens, tokens.length - 2, tokens.length));
        }

        int minChars = context.options().errorLogMessageQueryMinChars();
        int minTokens = context.options().errorLogMessageQueryMinTokens();
        int maxAttempts = context.options().errorLogMessageQueryMaxAttempts();
        return rawCandidates.stream()
                .map(String::trim)
                .filter(this::hasText)
                .collect(Collectors.collectingAndThen(
                        Collectors.toCollection(LinkedHashSet::new),
                        candidates -> candidates.stream()
                                .filter(candidate -> candidate.length() >= minChars)
                                .filter(candidate -> tokenCount(candidate) >= minTokens)
                                .limit(maxAttempts)
                                .toList()
                ));
    }

    private String joinTokens(String[] tokens, int startInclusive, int endExclusive) {
        StringBuilder builder = new StringBuilder();
        for (int index = startInclusive; index < endExclusive; index++) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(tokens[index]);
        }
        return builder.toString();
    }

    private int tokenCount(String value) {
        if (!hasText(value)) {
            return 0;
        }
        return value.trim().split("\\s+").length;
    }

    private List<String> sampleTraceIdsFromRuntimeLogs(List<RuntimeLogEntry> entries, AnalysisContext context) {
        return entries.stream()
                .map(RuntimeLogEntry::traceId)
                .filter(this::hasText)
                .collect(Collectors.collectingAndThen(
                        Collectors.toCollection(LinkedHashSet::new),
                        traceIds -> traceIds.stream()
                                .limit(context.options().maxTraceSampleSize())
                                .toList()
                ));
    }

    private Duration sumLatency(List<? extends ProviderResult<?>> results) {
        return results.stream()
                .map(ProviderResult::latency)
                .filter(Objects::nonNull)
                .reduce(Duration.ZERO, Duration::plus);
    }

    private ProviderResult<MetricEvidence> aggregateMetricResults(
            ProviderResult<ContainerMetrics> containerMetricResult,
            ProviderResult<CseMetrics> cseMetricResult,
            ProviderResult<DatabaseMetrics> databaseMetricResult,
            ProviderResult<DcsMetrics> dcsMetricResult,
            ProviderResult<MqMetrics> mqMetricResult
    ) {
        List<ProviderResult<?>> results = List.of(
                containerMetricResult,
                cseMetricResult,
                databaseMetricResult,
                dcsMetricResult,
                mqMetricResult
        );
        MetricEvidence evidence = MetricEvidence.available(
                containerMetricResult.isUsable() ? containerMetricResult.data() : ContainerMetrics.empty(),
                cseMetricResult.isUsable() ? cseMetricResult.data() : CseMetrics.empty(),
                databaseMetricResult.isUsable() ? databaseMetricResult.data() : DatabaseMetrics.empty(),
                dcsMetricResult.isUsable() ? dcsMetricResult.data() : DcsMetrics.empty(),
                mqMetricResult.isUsable() ? mqMetricResult.data() : MqMetrics.empty(),
                summarizeFailures(results)
        );
        return aggregateResults("metric", evidence, results, "metric queries skipped");
    }

    private <T> ProviderResult<T> aggregateResults(
            String providerName,
            T data,
            List<? extends ProviderResult<?>> results,
            String skippedReason
    ) {
        boolean anyUsable = results.stream().anyMatch(ProviderResult::isUsable);
        boolean anyFailure = results.stream().anyMatch(ProviderResult::isFailure);
        boolean anyPartial = results.stream().anyMatch(result -> result.status() == ProviderStatus.PARTIAL_SUCCESS);
        Duration latency = results.stream()
                .map(ProviderResult::latency)
                .filter(Objects::nonNull)
                .reduce(Duration.ZERO, Duration::plus);

        if (anyUsable && (anyFailure || anyPartial)) {
            return ProviderResult.partialSuccess(
                    providerName,
                    data,
                    "PARTIAL_PROVIDER_FAILURE",
                    summarizeFailures(results),
                    latency
            );
        }
        if (anyUsable) {
            return ProviderResult.success(providerName, data, latency);
        }
        if (anyFailure) {
            boolean allTimeout = results.stream()
                    .filter(result -> result.status() != ProviderStatus.SKIPPED)
                    .allMatch(result -> result.status() == ProviderStatus.TIMEOUT);
            if (allTimeout) {
                return ProviderResult.timeout(providerName, summarizeFailures(results), latency);
            }
            return ProviderResult.failure(
                    providerName,
                    "PROVIDER_EXECUTION_ERROR",
                    summarizeFailures(results),
                    latency
            );
        }
        return ProviderResult.skipped(providerName, skippedReason);
    }

    private BaselineEvidence toBaselineEvidence(ProviderResult<InterfaceBaseline> baselineResult) {
        if (baselineResult.isUsable() && baselineResult.data() != null) {
            return BaselineEvidence.available(baselineResult.data());
        }
        return BaselineEvidence.missing(baselineResult.errorMessage());
    }

    private InterfaceLogEvidence toInterfaceLogEvidence(
            ProviderResult<List<InterfaceLogEntry>> interfaceLogResult,
            List<String> sampledTraceIds
    ) {
        if (interfaceLogResult.isUsable() && interfaceLogResult.data() != null) {
            return InterfaceLogEvidence.available(interfaceLogResult.data(), sampledTraceIds);
        }
        return InterfaceLogEvidence.missing(interfaceLogResult.errorMessage());
    }

    private TraceEvidence toTraceEvidence(ProviderResult<List<TraceRecord>> traceResult, List<String> sampledTraceIds) {
        return toTraceEvidence(traceResult, sampledTraceIds, "interface logs");
    }

    private TraceEvidence toTraceEvidence(
            ProviderResult<List<TraceRecord>> traceResult,
            List<String> sampledTraceIds,
            String traceIdSource
    ) {
        if (traceResult.isUsable() && traceResult.data() != null) {
            return TraceEvidence.available(traceResult.data());
        }
        if (sampledTraceIds.isEmpty()) {
            return TraceEvidence.missing("no sampled trace ids available from " + traceIdSource);
        }
        return TraceEvidence.missing(traceResult.errorMessage());
    }

    private RuntimeLogEvidence toRuntimeLogEvidence(
            ProviderResult<List<RuntimeLogEntry>> runtimeLogResult,
            List<String> sampledTraceIds
    ) {
        if (runtimeLogResult.isUsable() && runtimeLogResult.data() != null) {
            return RuntimeLogEvidence.available(runtimeLogResult.data());
        }
        if (sampledTraceIds.isEmpty()) {
            return RuntimeLogEvidence.missing("no sampled trace ids available for runtime-log lookup");
        }
        return RuntimeLogEvidence.missing(runtimeLogResult.errorMessage());
    }

    private List<MissingEvidence> buildMissingEvidence(
            AlertCategory category,
            List<ProviderResult<?>> providerResults
    ) {
        List<MissingEvidence> missingEvidence = new ArrayList<>();
        List<MissingEvidence> deferredMissingEvidence = new ArrayList<>();
        for (ProviderResult<?> result : providerResults) {
            if (!hasMissingEvidence(result)) {
                continue;
            }
            MissingEvidence evidence = new MissingEvidence(
                    result.providerName(),
                    defaultIfBlank(result.errorMessage(), "provider evidence unavailable"),
                    isCriticalProvider(result.providerName(), category),
                    impactFor(result.providerName(), category)
            );
            if (PROVIDER_QUERY_MAPPING.equals(result.providerName())) {
                deferredMissingEvidence.add(evidence);
                continue;
            }
            missingEvidence.add(evidence);
        }
        missingEvidence.addAll(deferredMissingEvidence);
        return missingEvidence;
    }

    private boolean hasMissingEvidence(ProviderResult<?> result) {
        return result.status() == ProviderStatus.FAILED
                || result.status() == ProviderStatus.TIMEOUT
                || result.status() == ProviderStatus.PARTIAL_SUCCESS;
    }

    private boolean isCriticalProvider(String providerName, AlertCategory category) {
        if (PROVIDER_INTERFACE_LOG.equals(providerName)) {
            return category != AlertCategory.SERVICE_ERROR_LOG;
        }
        if (PROVIDER_RUNTIME_LOG.equals(providerName)) {
            return category == AlertCategory.SERVICE_ERROR_LOG;
        }
        return category == AlertCategory.RPC_LATENCY && PROVIDER_TRACE.equals(providerName);
    }

    private String impactFor(String providerName, AlertCategory category) {
        return switch (providerName) {
            case PROVIDER_INTERFACE_LOG ->
                    "Cannot locate errorCode, uid, trace topology, or interface latency distribution.";
            case PROVIDER_TRACE ->
                    category == AlertCategory.RPC_LATENCY
                            ? "Cannot verify downstream slow spans for the latency alert."
                            : "Downstream trace topology is incomplete.";
            case PROVIDER_RUNTIME_LOG ->
                    category == AlertCategory.SERVICE_ERROR_LOG
                            ? "Cannot locate the ERROR runtime log context that triggered this service-level alert."
                            : "WARN/ERROR runtime log samples are unavailable for this alert window.";
            case PROVIDER_BASELINE ->
                    "Historical comparison for the same time window is unavailable.";
            case PROVIDER_QUERY_MAPPING ->
                    "Provider-specific query target mapping is unavailable; provider calls used alert service and cluster values.";
            case PROVIDER_RESOURCE_MAPPING ->
                    "DB/DCS/MQ metric lookups may be incomplete because resource mapping is unavailable.";
            case PROVIDER_METRIC_CONTAINER ->
                    "Service-side container pressure cannot be verified.";
            case PROVIDER_METRIC_CSE ->
                    "RPC framework pressure cannot be verified.";
            case PROVIDER_METRIC_DATABASE ->
                    "Database pressure cannot be verified.";
            case PROVIDER_METRIC_DCS ->
                    "DCS/cache pressure cannot be verified.";
            case PROVIDER_METRIC_MQ ->
                    "MQ pressure cannot be verified.";
            default -> "Provider evidence is incomplete.";
        };
    }

    private String buildDegradedReason(
            boolean interfaceLogUnavailable,
            boolean latencyTraceUnavailable,
            boolean nonCriticalFailure,
            List<MissingEvidence> missingEvidence
    ) {
        if (interfaceLogUnavailable) {
            return "Interface logs are unavailable, so the task cannot locate errorCode, uid, trace, or latency distribution.";
        }
        if (latencyTraceUnavailable) {
            return "Trace evidence is unavailable for a latency alert, so downstream slow-point confirmation is not possible.";
        }
        if (nonCriticalFailure && !missingEvidence.isEmpty()) {
            return "Provider collection finished with missing evidence from: "
                    + missingEvidence.stream().map(MissingEvidence::providerName).distinct().collect(Collectors.joining(", "));
        }
        return null;
    }

    private String buildServiceErrorLogDegradedReason(
            boolean runtimeLogUnavailable,
            boolean nonCriticalFailure,
            List<MissingEvidence> missingEvidence
    ) {
        if (runtimeLogUnavailable) {
            return "Could not locate ERROR runtime log context from alert message queries.";
        }
        if (nonCriticalFailure && !missingEvidence.isEmpty()) {
            return "Provider collection finished with missing evidence from: "
                    + missingEvidence.stream().map(MissingEvidence::providerName).distinct().collect(Collectors.joining(", "));
        }
        return null;
    }

    private String summarizeFailures(List<? extends ProviderResult<?>> results) {
        String summary = results.stream()
                .filter(this::hasMissingEvidence)
                .map(result -> result.providerName() + "=" + defaultIfBlank(result.errorMessage(), result.status().name()))
                .distinct()
                .collect(Collectors.joining("; "));
        return summary.isBlank() ? null : summary;
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
