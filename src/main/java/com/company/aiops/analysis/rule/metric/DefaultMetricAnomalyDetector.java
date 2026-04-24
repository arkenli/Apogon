package com.company.aiops.analysis.rule.metric;

import com.company.aiops.analysis.evidence.MetricEvidence;
import com.company.aiops.analysis.evidence.ProviderCollectionResult;
import com.company.aiops.common.config.AiopsProperties;
import com.company.aiops.provider.model.ContainerMetrics;
import com.company.aiops.provider.model.CseMetrics;
import com.company.aiops.provider.model.DatabaseMetrics;
import com.company.aiops.provider.model.DcsMetrics;
import com.company.aiops.provider.model.MqMetrics;
import com.company.aiops.analysis.rule.model.MetricAnomaly;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Snapshot-based anomaly detector for the phase-5 mock/provider contracts.
 *
 * <p>The current provider layer exposes aggregated snapshots instead of full time-series data, so
 * the detector focuses on thresholds, single-instance skew, and high-signal counters. This keeps
 * the rule-analysis stage deterministic and compatible with the current provider contracts.</p>
 */
@Component
public class DefaultMetricAnomalyDetector implements MetricAnomalyDetector {

    private static final long YOUNG_GC_SPIKE_THRESHOLD = 10L;
    private static final long DATABASE_WRITE_LATENCY_THRESHOLD_MS = 100L;
    private static final long DCS_P99_LATENCY_THRESHOLD_MS = 200L;
    private static final long DCS_AVG_LATENCY_THRESHOLD_MS = 100L;
    private static final double DCS_SLOW_QUERY_RATIO_THRESHOLD = 0.05;
    private static final long MQ_BACKLOG_THRESHOLD = 1000L;
    private static final long MQ_LATENCY_THRESHOLD_MS = 500L;

    private final AiopsProperties aiopsProperties;

    public DefaultMetricAnomalyDetector(AiopsProperties aiopsProperties) {
        this.aiopsProperties = aiopsProperties;
    }

    @Override
    public List<MetricAnomaly> detect(ProviderCollectionResult collectionResult) {
        MetricEvidence metrics = collectionResult.metricEvidence();
        List<MetricAnomaly> anomalies = new ArrayList<>();
        Map<String, Integer> counters = new HashMap<>();

        detectContainerMetrics(collectionResult, metrics.containerMetrics(), anomalies, counters);
        detectCseMetrics(collectionResult, metrics.cseMetrics(), anomalies, counters);
        detectDatabaseMetrics(metrics.databaseMetrics(), anomalies, counters);
        detectDcsMetrics(metrics.dcsMetrics(), anomalies, counters);
        detectMqMetrics(metrics.mqMetrics(), anomalies, counters);

        return anomalies.stream()
                .sorted(Comparator.comparing(MetricAnomaly::evidenceId))
                .toList();
    }

    private void detectContainerMetrics(
            ProviderCollectionResult collectionResult,
            ContainerMetrics metrics,
            List<MetricAnomaly> anomalies,
            Map<String, Integer> counters
    ) {
        String resourceId = collectionResult.context().alert().serviceName();
        if (metrics.averageCpuUsage() >= aiopsProperties.thresholds().containerCpuHigh()
                || metrics.maxCpuUsage() >= aiopsProperties.thresholds().containerCpuHigh()) {
            anomalies.add(anomaly(
                    counters,
                    "metric.container.cpu",
                    "CONTAINER",
                    resourceId,
                    "cpuUsage",
                    Math.max(metrics.averageCpuUsage(), metrics.maxCpuUsage()),
                    aiopsProperties.thresholds().containerCpuHigh(),
                    severity(Math.max(metrics.averageCpuUsage(), metrics.maxCpuUsage()), aiopsProperties.thresholds().containerCpuHigh()),
                    "Container CPU usage exceeds the configured threshold."
            ));
        }
        if (metrics.averageMemoryUsage() >= aiopsProperties.thresholds().containerMemoryHigh()
                || metrics.maxMemoryUsage() >= aiopsProperties.thresholds().containerMemoryHigh()) {
            anomalies.add(anomaly(
                    counters,
                    "metric.container.memory",
                    "CONTAINER",
                    resourceId,
                    "memoryUsage",
                    Math.max(metrics.averageMemoryUsage(), metrics.maxMemoryUsage()),
                    aiopsProperties.thresholds().containerMemoryHigh(),
                    severity(Math.max(metrics.averageMemoryUsage(), metrics.maxMemoryUsage()), aiopsProperties.thresholds().containerMemoryHigh()),
                    "Container memory usage exceeds the configured threshold."
            ));
        }
        if (metrics.fullGcCount() > 0) {
            anomalies.add(anomaly(
                    counters,
                    "metric.container.full_gc",
                    "CONTAINER",
                    resourceId,
                    "fullGcCount",
                    metrics.fullGcCount(),
                    0.0,
                    "HIGH",
                    "Full GC occurred during the alert window."
            ));
        }
        if (metrics.youngGcCount() >= YOUNG_GC_SPIKE_THRESHOLD) {
            anomalies.add(anomaly(
                    counters,
                    "metric.container.young_gc",
                    "CONTAINER",
                    resourceId,
                    "youngGcCount",
                    metrics.youngGcCount(),
                    (double) YOUNG_GC_SPIKE_THRESHOLD,
                    "MEDIUM",
                    "Young GC count is unusually high for the alert window."
            ));
        }
        if (metrics.startingInstanceCount() > 0 || metrics.failedInstanceCount() > 0) {
            anomalies.add(anomaly(
                    counters,
                    "metric.container.instance_state",
                    "CONTAINER",
                    resourceId,
                    "instanceState",
                    metrics.startingInstanceCount() + metrics.failedInstanceCount(),
                    0.0,
                    "HIGH",
                    "One or more instances are starting or failed."
            ));
        }
        double medianCpu = median(metrics.instances().stream().map(ContainerMetrics.InstanceMetrics::cpuUsage).toList());
        for (ContainerMetrics.InstanceMetrics instance : metrics.instances()) {
            double skewThreshold = Math.max(aiopsProperties.thresholds().containerCpuHigh(), medianCpu * 2);
            if (medianCpu > 0 && instance.cpuUsage() >= skewThreshold) {
                anomalies.add(anomaly(
                        counters,
                        "metric.container.instance_cpu_skew",
                        "CONTAINER_INSTANCE",
                        instance.instanceId(),
                        "cpuUsage",
                        instance.cpuUsage(),
                        skewThreshold,
                        "MEDIUM",
                        "One instance CPU usage is significantly higher than peer instances."
                ));
            }
        }
    }

    private void detectCseMetrics(
            ProviderCollectionResult collectionResult,
            CseMetrics metrics,
            List<MetricAnomaly> anomalies,
            Map<String, Integer> counters
    ) {
        String resourceId = collectionResult.context().alert().serviceName();
        if (metrics.threadPoolUsage() >= aiopsProperties.thresholds().cseUsageHigh()) {
            anomalies.add(anomaly(
                    counters,
                    "metric.cse.thread_pool",
                    "CSE",
                    resourceId,
                    "threadPoolUsage",
                    metrics.threadPoolUsage(),
                    aiopsProperties.thresholds().cseUsageHigh(),
                    severity(metrics.threadPoolUsage(), aiopsProperties.thresholds().cseUsageHigh()),
                    "RPC thread-pool usage exceeds the configured threshold."
            ));
        }
        if (metrics.connectionUsage() >= aiopsProperties.thresholds().cseUsageHigh()) {
            anomalies.add(anomaly(
                    counters,
                    "metric.cse.connection",
                    "CSE",
                    resourceId,
                    "connectionUsage",
                    metrics.connectionUsage(),
                    aiopsProperties.thresholds().cseUsageHigh(),
                    severity(metrics.connectionUsage(), aiopsProperties.thresholds().cseUsageHigh()),
                    "RPC connection usage exceeds the configured threshold."
            ));
        }
    }

    private void detectDatabaseMetrics(
            DatabaseMetrics metrics,
            List<MetricAnomaly> anomalies,
            Map<String, Integer> counters
    ) {
        for (DatabaseMetrics.InstanceMetrics instance : metrics.instances().stream()
                .sorted(Comparator.comparing(DatabaseMetrics.InstanceMetrics::instanceId))
                .toList()) {
            if (instance.cpuUsage() >= aiopsProperties.thresholds().dbCpuHigh()) {
                anomalies.add(anomaly(
                        counters,
                        "metric.database.cpu",
                        "DATABASE",
                        instance.instanceId(),
                        "cpuUsage",
                        instance.cpuUsage(),
                        aiopsProperties.thresholds().dbCpuHigh(),
                        severity(instance.cpuUsage(), aiopsProperties.thresholds().dbCpuHigh()),
                        "Database CPU usage exceeds the configured threshold."
                ));
            }
            if (instance.connectionUsage() >= aiopsProperties.thresholds().dbConnectionHigh()) {
                anomalies.add(anomaly(
                        counters,
                        "metric.database.connection",
                        "DATABASE",
                        instance.instanceId(),
                        "connectionUsage",
                        instance.connectionUsage(),
                        aiopsProperties.thresholds().dbConnectionHigh(),
                        severity(instance.connectionUsage(), aiopsProperties.thresholds().dbConnectionHigh()),
                        "Database connection usage exceeds the configured threshold."
                ));
            }
            if (instance.rowLockWaitCount() > 0) {
                anomalies.add(anomaly(
                        counters,
                        "metric.database.lock_wait",
                        "DATABASE",
                        instance.instanceId(),
                        "rowLockWaitCount",
                        instance.rowLockWaitCount(),
                        0.0,
                        "HIGH",
                        "Database row-lock wait count is non-zero."
                ));
            }
            if (instance.maxStorageWriteLatencyMs() >= DATABASE_WRITE_LATENCY_THRESHOLD_MS) {
                anomalies.add(anomaly(
                        counters,
                        "metric.database.write_latency",
                        "DATABASE",
                        instance.instanceId(),
                        "writeLatencyMs",
                        instance.maxStorageWriteLatencyMs(),
                        (double) DATABASE_WRITE_LATENCY_THRESHOLD_MS,
                        "HIGH",
                        "Database write latency is unusually high."
                ));
            }
            if (instance.slowSqlCount() > 0 || instance.slowLogCount() > 0) {
                anomalies.add(anomaly(
                        counters,
                        "metric.database.slow_sql",
                        "DATABASE",
                        instance.instanceId(),
                        "slowSqlCount",
                        Math.max(instance.slowSqlCount(), instance.slowLogCount()),
                        0.0,
                        "MEDIUM",
                        "Database slow SQL or slow log counters are non-zero."
                ));
            }
        }
    }

    private void detectDcsMetrics(
            DcsMetrics metrics,
            List<MetricAnomaly> anomalies,
            Map<String, Integer> counters
    ) {
        for (DcsMetrics.InstanceMetrics instance : metrics.instances().stream()
                .sorted(Comparator.comparing(DcsMetrics.InstanceMetrics::instanceId))
                .toList()) {
            if (instance.cpuUsage() >= aiopsProperties.thresholds().dcsCpuHigh()) {
                anomalies.add(anomaly(
                        counters,
                        "metric.dcs.cpu",
                        "DCS",
                        instance.instanceId(),
                        "cpuUsage",
                        instance.cpuUsage(),
                        aiopsProperties.thresholds().dcsCpuHigh(),
                        severity(instance.cpuUsage(), aiopsProperties.thresholds().dcsCpuHigh()),
                        "DCS CPU usage exceeds the configured threshold."
                ));
            }
            if (instance.memoryUsage() >= aiopsProperties.thresholds().dcsMemoryHigh()) {
                anomalies.add(anomaly(
                        counters,
                        "metric.dcs.memory",
                        "DCS",
                        instance.instanceId(),
                        "memoryUsage",
                        instance.memoryUsage(),
                        aiopsProperties.thresholds().dcsMemoryHigh(),
                        severity(instance.memoryUsage(), aiopsProperties.thresholds().dcsMemoryHigh()),
                        "DCS memory usage exceeds the configured threshold."
                ));
            }
            if (instance.p99LatencyMs() >= DCS_P99_LATENCY_THRESHOLD_MS) {
                anomalies.add(anomaly(
                        counters,
                        "metric.dcs.p99",
                        "DCS",
                        instance.instanceId(),
                        "p99LatencyMs",
                        instance.p99LatencyMs(),
                        (double) DCS_P99_LATENCY_THRESHOLD_MS,
                        "HIGH",
                        "DCS P99 latency is unusually high."
                ));
            }
            if (instance.avgLatencyMs() >= DCS_AVG_LATENCY_THRESHOLD_MS) {
                anomalies.add(anomaly(
                        counters,
                        "metric.dcs.avg",
                        "DCS",
                        instance.instanceId(),
                        "avgLatencyMs",
                        instance.avgLatencyMs(),
                        (double) DCS_AVG_LATENCY_THRESHOLD_MS,
                        "MEDIUM",
                        "DCS average latency is unusually high."
                ));
            }
            if (instance.slowQueryRatio() >= DCS_SLOW_QUERY_RATIO_THRESHOLD) {
                anomalies.add(anomaly(
                        counters,
                        "metric.dcs.slow_query",
                        "DCS",
                        instance.instanceId(),
                        "slowQueryRatio",
                        instance.slowQueryRatio(),
                        DCS_SLOW_QUERY_RATIO_THRESHOLD,
                        "HIGH",
                        "DCS slow-query ratio exceeds the configured threshold."
                ));
            }
            if (instance.clientFailureCount() > 0) {
                anomalies.add(anomaly(
                        counters,
                        "metric.dcs.client_failure",
                        "DCS",
                        instance.instanceId(),
                        "clientFailureCount",
                        instance.clientFailureCount(),
                        0.0,
                        "HIGH",
                        "DCS client failures were observed during the alert window."
                ));
            }
        }
    }

    private void detectMqMetrics(
            MqMetrics metrics,
            List<MetricAnomaly> anomalies,
            Map<String, Integer> counters
    ) {
        for (MqMetrics.InstanceMetrics instance : metrics.instances().stream()
                .sorted(Comparator.comparing(MqMetrics.InstanceMetrics::instanceId))
                .toList()) {
            if (instance.backlogSize() >= MQ_BACKLOG_THRESHOLD) {
                anomalies.add(anomaly(
                        counters,
                        "metric.mq.backlog",
                        "MQ",
                        instance.instanceId(),
                        "backlogSize",
                        instance.backlogSize(),
                        (double) MQ_BACKLOG_THRESHOLD,
                        "HIGH",
                        "MQ backlog size exceeds the configured threshold."
                ));
            }
            if (instance.publishLatencyMs() >= MQ_LATENCY_THRESHOLD_MS) {
                anomalies.add(anomaly(
                        counters,
                        "metric.mq.publish_latency",
                        "MQ",
                        instance.instanceId(),
                        "publishLatencyMs",
                        instance.publishLatencyMs(),
                        (double) MQ_LATENCY_THRESHOLD_MS,
                        "MEDIUM",
                        "MQ publish latency is unusually high."
                ));
            }
            if (instance.consumeLatencyMs() >= MQ_LATENCY_THRESHOLD_MS) {
                anomalies.add(anomaly(
                        counters,
                        "metric.mq.consume_latency",
                        "MQ",
                        instance.instanceId(),
                        "consumeLatencyMs",
                        instance.consumeLatencyMs(),
                        (double) MQ_LATENCY_THRESHOLD_MS,
                        "MEDIUM",
                        "MQ consume latency is unusually high."
                ));
            }
            if (instance.failedRequestCount() > 0) {
                anomalies.add(anomaly(
                        counters,
                        "metric.mq.failed_request",
                        "MQ",
                        instance.instanceId(),
                        "failedRequestCount",
                        instance.failedRequestCount(),
                        0.0,
                        "HIGH",
                        "MQ request failures were observed during the alert window."
                ));
            }
        }
    }

    private MetricAnomaly anomaly(
            Map<String, Integer> counters,
            String evidencePrefix,
            String resourceType,
            String resourceId,
            String metric,
            double value,
            Double referenceValue,
            String severity,
            String description
    ) {
        return new MetricAnomaly(
                nextEvidenceId(counters, evidencePrefix),
                resourceType,
                resourceId,
                metric,
                value,
                referenceValue,
                severity,
                description
        );
    }

    private String nextEvidenceId(Map<String, Integer> counters, String prefix) {
        int next = counters.merge(prefix, 1, Integer::sum);
        return prefix + "." + next;
    }

    private String severity(double value, double threshold) {
        if (threshold <= 0) {
            return "HIGH";
        }
        return value >= threshold * 1.2 ? "HIGH" : "MEDIUM";
    }

    private double median(List<Double> values) {
        List<Double> sorted = values.stream()
                .filter(value -> value != null && !value.isNaN())
                .sorted()
                .toList();
        if (sorted.isEmpty()) {
            return 0.0;
        }
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 0) {
            return (sorted.get(middle - 1) + sorted.get(middle)) / 2.0;
        }
        return sorted.get(middle);
    }
}
