package com.company.aiops.alert;

import com.company.aiops.analysis.HistoricalReference;

public record AlertWebhookResponse(
        String alertId,
        String taskId,
        String status,
        boolean duplicate,
        HistoricalReference historicalReference,
        String message
) {
}
