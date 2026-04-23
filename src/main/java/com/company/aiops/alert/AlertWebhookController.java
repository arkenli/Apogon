package com.company.aiops.alert;

import com.company.aiops.common.api.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/alerts")
public class AlertWebhookController {

    private final AlertIngestionService alertIngestionService;

    public AlertWebhookController(AlertIngestionService alertIngestionService) {
        this.alertIngestionService = alertIngestionService;
    }

    @PostMapping(value = "/webhook", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ApiResponse<AlertWebhookResponse> receiveWebhook(@RequestBody String rawText) {
        return ApiResponse.ok(alertIngestionService.ingest(rawText), "analysis task accepted");
    }
}
