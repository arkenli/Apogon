package com.company.aiops.alert;

import java.time.Instant;

public record AlertEvent(
        String alertId,
        AlertCategory category,
        String status,
        String severity,
        String serviceName,
        String interfaceName,
        String cluster,
        String site,
        String tenant,
        String application,
        String alertName,
        String metricName,
        String rawCategory,
        String rawText,
        Instant metricOccurTime,
        Instant receivedAt,
        AlertTriggerValues triggerValues
) {

    public AlertEvent withCategory(AlertCategory nextCategory) {
        return new AlertEvent(
                alertId,
                nextCategory,
                status,
                severity,
                serviceName,
                interfaceName,
                cluster,
                site,
                tenant,
                application,
                alertName,
                metricName,
                rawCategory,
                rawText,
                metricOccurTime,
                receivedAt,
                triggerValues
        );
    }
}
