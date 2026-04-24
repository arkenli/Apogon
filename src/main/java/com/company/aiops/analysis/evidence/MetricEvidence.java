package com.company.aiops.analysis.evidence;

import com.company.aiops.provider.model.ContainerMetrics;
import com.company.aiops.provider.model.CseMetrics;
import com.company.aiops.provider.model.DatabaseMetrics;
import com.company.aiops.provider.model.DcsMetrics;
import com.company.aiops.provider.model.MqMetrics;

/**
 * Aggregated infrastructure metrics collected from the metric provider.
 */
public record MetricEvidence(
        ContainerMetrics containerMetrics,
        CseMetrics cseMetrics,
        DatabaseMetrics databaseMetrics,
        DcsMetrics dcsMetrics,
        MqMetrics mqMetrics,
        boolean collected,
        String missingReason
) {

    public MetricEvidence {
        containerMetrics = containerMetrics == null ? ContainerMetrics.empty() : containerMetrics;
        cseMetrics = cseMetrics == null ? CseMetrics.empty() : cseMetrics;
        databaseMetrics = databaseMetrics == null ? DatabaseMetrics.empty() : databaseMetrics;
        dcsMetrics = dcsMetrics == null ? DcsMetrics.empty() : dcsMetrics;
        mqMetrics = mqMetrics == null ? MqMetrics.empty() : mqMetrics;
    }

    public static MetricEvidence available(
            ContainerMetrics containerMetrics,
            CseMetrics cseMetrics,
            DatabaseMetrics databaseMetrics,
            DcsMetrics dcsMetrics,
            MqMetrics mqMetrics,
            String missingReason
    ) {
        return new MetricEvidence(
                containerMetrics,
                cseMetrics,
                databaseMetrics,
                dcsMetrics,
                mqMetrics,
                true,
                missingReason
        );
    }

    public static MetricEvidence missing(String reason) {
        return new MetricEvidence(
                ContainerMetrics.empty(),
                CseMetrics.empty(),
                DatabaseMetrics.empty(),
                DcsMetrics.empty(),
                MqMetrics.empty(),
                false,
                reason
        );
    }
}
