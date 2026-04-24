package com.company.aiops.alert.model;

import java.time.Instant;

/**
 * 结构化告警事件，承载接入阶段解析出的核心字段。
 */
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

    /**
     * 返回仅替换分类结果的新告警对象，保留其他解析字段不变。
     */
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
