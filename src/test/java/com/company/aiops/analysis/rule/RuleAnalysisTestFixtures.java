package com.company.aiops.analysis.rule;

import com.company.aiops.alert.model.AlertCategory;
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
import com.company.aiops.common.config.AiopsProperties;
import com.company.aiops.provider.model.ResourceMapping;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Shared phase-5 test fixtures.
 */
public final class RuleAnalysisTestFixtures {

    private RuleAnalysisTestFixtures() {
    }

    public static AiopsProperties defaultProperties() {
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
                        Duration.ofSeconds(10),
                        1,
                        Duration.ofMillis(100),
                        true,
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

    public static AlertEvent errorRateAlert(double requestCount) {
        Instant occurTime = Instant.parse("2026-04-20T10:09:00Z");
        return new AlertEvent(
                "alert-error-rate",
                AlertCategory.RPC_ERROR_RATE,
                "OPEN",
                "MAJOR",
                "ams_front",
                "vmall-ams.PrizeActivityService.queryPrizeResult",
                "cluster1",
                "cn_product_default",
                "tenant",
                "application",
                "error-rate alert",
                "rpc_error_rate",
                "AIOps@rate",
                "raw alert",
                occurTime,
                occurTime,
                new AlertTriggerValues(requestCount, 1.0, null, null, null, null, null, null, null, "failRate > 0.7", "raw")
        );
    }

    public static AlertEvent latencyAlert(double requestCount, long avgLatencyMs) {
        Instant occurTime = Instant.parse("2026-04-21T02:33:00Z");
        return new AlertEvent(
                "alert-latency",
                AlertCategory.RPC_LATENCY,
                "OPEN",
                "MAJOR",
                "vmall_ingresscache_ica",
                "persistent://WiseCloudDMQService/WiseCloudDMQService/MQTopic_PMS.refreshAppProduct_cluster2",
                "cluster2",
                "cn_product_default",
                "tenant",
                "application",
                "latency alert",
                "rpc_latency",
                "AIOps@delay",
                "raw alert",
                occurTime,
                occurTime,
                new AlertTriggerValues(requestCount, null, avgLatencyMs, null, null, null, null, null, null, "avgLatency > 3000", "raw")
        );
    }

    public static AlertEvent serviceErrorLogAlert() {
        Instant occurTime = Instant.parse("2026-04-22T03:15:00Z");
        return new AlertEvent(
                "alert-service-error-log",
                AlertCategory.SERVICE_ERROR_LOG,
                "OPEN",
                "MAJOR",
                "ams_front",
                null,
                "cluster2",
                "cn_product_default",
                "tenant",
                "application",
                "service error log alert",
                "service_error_log",
                "AIOps@serviceLog",
                "raw alert",
                occurTime,
                occurTime,
                new AlertTriggerValues(
                        null,
                        null,
                        null,
                        null,
                        null,
                        2.0,
                        "ERROR",
                        "DEFAULT prebook loginAppointment failed",
                        "prebook loginAppointment failed",
                        "failureCount > 1",
                        "raw"
                )
        );
    }

    public static ProviderCollectionResult collection(
            AlertEvent alert,
            BaselineEvidence baselineEvidence,
            InterfaceLogEvidence interfaceLogEvidence,
            TraceEvidence traceEvidence,
            RuntimeLogEvidence runtimeLogEvidence,
            MetricEvidence metricEvidence
    ) {
        AnalysisContext context = new AnalysisContext(
                alert,
                new TimeWindow(alert.metricOccurTime().minus(Duration.ofMinutes(10)), alert.metricOccurTime().plus(Duration.ofMinutes(2))),
                new TimeWindow(alert.metricOccurTime().minus(Duration.ofDays(1)).minus(Duration.ofMinutes(5)), alert.metricOccurTime().minus(Duration.ofDays(1)).plus(Duration.ofMinutes(5))),
                ResourceMapping.empty(alert.serviceName(), alert.cluster()),
                new AnalysisOptions(2000, 20, 20, 50, 500, 10, 10, 5, 8, 2, "ERROR", false, true)
        );
        return new ProviderCollectionResult(
                context,
                baselineEvidence,
                interfaceLogEvidence,
                traceEvidence,
                runtimeLogEvidence,
                metricEvidence,
                List.of(),
                false,
                false,
                null,
                List.of()
        );
    }
}
