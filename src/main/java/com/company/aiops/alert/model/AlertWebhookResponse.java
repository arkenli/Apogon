package com.company.aiops.alert.model;

import com.company.aiops.analysis.model.HistoricalReference;

/**
 * Webhook 接口返回的任务受理结果。
 */
public record AlertWebhookResponse(
        String alertId,
        String taskId,
        String status,
        boolean duplicate,
        HistoricalReference historicalReference,
        String message
) {
}
