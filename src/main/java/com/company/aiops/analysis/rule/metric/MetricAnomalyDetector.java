package com.company.aiops.analysis.rule.metric;

import com.company.aiops.analysis.evidence.ProviderCollectionResult;
import com.company.aiops.analysis.rule.model.MetricAnomaly;

import java.util.List;

/**
 * Detects deterministic anomalies from collected metric snapshots.
 */
public interface MetricAnomalyDetector {

    /**
     * Produces metric anomalies that rule analyzers can score and reference by evidence id.
     */
    List<MetricAnomaly> detect(ProviderCollectionResult collectionResult);
}
