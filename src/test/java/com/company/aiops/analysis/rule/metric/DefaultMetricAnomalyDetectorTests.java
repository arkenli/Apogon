package com.company.aiops.analysis.rule.metric;

import com.company.aiops.analysis.evidence.BaselineEvidence;
import com.company.aiops.analysis.evidence.InterfaceLogEvidence;
import com.company.aiops.analysis.evidence.MetricEvidence;
import com.company.aiops.analysis.evidence.RuntimeLogEvidence;
import com.company.aiops.analysis.evidence.TraceEvidence;
import com.company.aiops.analysis.rule.RuleAnalysisTestFixtures;
import com.company.aiops.analysis.rule.model.MetricAnomaly;
import com.company.aiops.provider.model.ContainerMetrics;
import com.company.aiops.provider.model.CseMetrics;
import com.company.aiops.provider.model.DatabaseMetrics;
import com.company.aiops.provider.model.DcsMetrics;
import com.company.aiops.provider.model.MqMetrics;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultMetricAnomalyDetectorTests {

    @Test
    void detectsSnapshotAnomaliesAcrossContainerDatabaseAndDcs() {
        DefaultMetricAnomalyDetector detector = new DefaultMetricAnomalyDetector(RuleAnalysisTestFixtures.defaultProperties());
        MetricEvidence metricEvidence = MetricEvidence.available(
                new ContainerMetrics(
                        0.91,
                        0.95,
                        0.88,
                        0.92,
                        1,
                        0,
                        15L,
                        1L,
                        List.of(
                                new ContainerMetrics.InstanceMetrics("svc-pod-1", 0.10, 0.50, false, false),
                                new ContainerMetrics.InstanceMetrics("svc-pod-2", 0.20, 0.60, false, false),
                                new ContainerMetrics.InstanceMetrics("svc-pod-3", 0.95, 0.89, false, false)
                        )
                ),
                new CseMetrics(0.85, 0.20),
                new DatabaseMetrics(
                        List.of(new DatabaseMetrics.InstanceMetrics(
                                "db-1",
                                0.81,
                                0.50,
                                0.90,
                                100L,
                                3L,
                                0.40,
                                10.0,
                                10.0,
                                160L,
                                5L,
                                3L,
                                3L
                        )),
                        List.of(new DatabaseMetrics.SlowSqlSample("db-1", "select 1", 250L))
                ),
                new DcsMetrics(List.of(new DcsMetrics.InstanceMetrics("dcs-1", 0.20, 0.20, 300.0, 0.10, 300L, 150L, 2L))),
                MqMetrics.empty(),
                null
        );

        List<MetricAnomaly> anomalies = detector.detect(RuleAnalysisTestFixtures.collection(
                RuleAnalysisTestFixtures.errorRateAlert(20),
                BaselineEvidence.missing("n/a"),
                InterfaceLogEvidence.missing("n/a"),
                TraceEvidence.missing("n/a"),
                RuntimeLogEvidence.missing("n/a"),
                metricEvidence
        ));

        assertThat(anomalies).extracting(MetricAnomaly::resourceType)
                .contains("CONTAINER", "CONTAINER_INSTANCE", "CSE", "DATABASE", "DCS");
        assertThat(anomalies).extracting(MetricAnomaly::evidenceId)
                .anyMatch(id -> id.startsWith("metric.container.cpu."))
                .anyMatch(id -> id.startsWith("metric.database.slow_sql."))
                .anyMatch(id -> id.startsWith("metric.dcs.p99."));
    }

    @Test
    void detectsDatabaseSlowSqlSignalsAsIndependentEvidence() {
        DefaultMetricAnomalyDetector detector = new DefaultMetricAnomalyDetector(RuleAnalysisTestFixtures.defaultProperties());
        MetricEvidence metricEvidence = MetricEvidence.available(
                ContainerMetrics.empty(),
                CseMetrics.empty(),
                new DatabaseMetrics(
                        List.of(new DatabaseMetrics.InstanceMetrics(
                                "db-1",
                                0.45,
                                0.52,
                                0.40,
                                83L,
                                2L,
                                0.48,
                                12.0,
                                10.0,
                                22L,
                                4L,
                                3L,
                                3L
                        )),
                        List.of(new DatabaseMetrics.SlowSqlSample("db-1", "select * from activity where id = ?", 250L))
                ),
                DcsMetrics.empty(),
                MqMetrics.empty(),
                null
        );

        List<MetricAnomaly> anomalies = detector.detect(RuleAnalysisTestFixtures.collection(
                RuleAnalysisTestFixtures.errorRateAlert(20),
                BaselineEvidence.missing("n/a"),
                InterfaceLogEvidence.missing("n/a"),
                TraceEvidence.missing("n/a"),
                RuntimeLogEvidence.missing("n/a"),
                metricEvidence
        ));

        assertThat(anomalies)
                .filteredOn(anomaly -> "DATABASE".equals(anomaly.resourceType()))
                .extracting(MetricAnomaly::evidenceId)
                .contains("metric.database.slow_sql.1");
    }

    @Test
    void detectsDcsP99RiseAsIndependentEvidence() {
        DefaultMetricAnomalyDetector detector = new DefaultMetricAnomalyDetector(RuleAnalysisTestFixtures.defaultProperties());
        MetricEvidence metricEvidence = MetricEvidence.available(
                ContainerMetrics.empty(),
                CseMetrics.empty(),
                DatabaseMetrics.empty(),
                new DcsMetrics(List.of(new DcsMetrics.InstanceMetrics("dcs-1", 0.20, 0.20, 300.0, 0.02, 300L, 150L, 0L))),
                MqMetrics.empty(),
                null
        );

        List<MetricAnomaly> anomalies = detector.detect(RuleAnalysisTestFixtures.collection(
                RuleAnalysisTestFixtures.latencyAlert(49, 3067L),
                BaselineEvidence.missing("n/a"),
                InterfaceLogEvidence.missing("n/a"),
                TraceEvidence.missing("n/a"),
                RuntimeLogEvidence.missing("n/a"),
                metricEvidence
        ));

        assertThat(anomalies)
                .filteredOn(anomaly -> "DCS".equals(anomaly.resourceType()))
                .extracting(MetricAnomaly::evidenceId)
                .contains("metric.dcs.p99.1");
    }
}
