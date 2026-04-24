package com.company.aiops.alert.classifier;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.alert.model.AlertEvent;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 默认告警分类器，优先使用原始分类字段，再回退到指标名关键字。
 */
@Component
public class DefaultAlertClassifier implements AlertClassifier {

    @Override
    public AlertCategory classify(AlertEvent alert) {
        if ("AIOps@rate".equals(alert.rawCategory())) {
            return AlertCategory.RPC_ERROR_RATE;
        }
        if ("AIOps@delay".equals(alert.rawCategory())) {
            return AlertCategory.RPC_LATENCY;
        }

        String metricName = alert.metricName();
        if (metricName == null || metricName.isBlank()) {
            return AlertCategory.UNKNOWN;
        }

        String normalizedMetric = metricName.toLowerCase(Locale.ROOT);
        if (normalizedMetric.contains("failrate")
                || normalizedMetric.contains("successrate")
                || normalizedMetric.contains("error")) {
            return AlertCategory.RPC_ERROR_RATE;
        }
        if (normalizedMetric.contains("delay")
                || normalizedMetric.contains("latency")
                || normalizedMetric.contains("p99")
                || normalizedMetric.contains("avg")) {
            return AlertCategory.RPC_LATENCY;
        }
        return AlertCategory.UNKNOWN;
    }
}
