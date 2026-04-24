package com.company.aiops.alert.controller;

import com.company.aiops.alert.model.AlertWebhookResponse;
import com.company.aiops.alert.service.AlertIngestionService;
import com.company.aiops.common.api.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接收 WiseEye Webhook 并将原始告警交给接入服务处理。
 */
@RestController
@RequestMapping("/api/v1/alerts")
public class AlertWebhookController {

    private final AlertIngestionService alertIngestionService;

    public AlertWebhookController(AlertIngestionService alertIngestionService) {
        this.alertIngestionService = alertIngestionService;
    }

    /**
     * 接收原始告警文本并返回分析任务受理结果。
     */
    @PostMapping(value = "/webhook", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ApiResponse<AlertWebhookResponse> receiveWebhook(@RequestBody String rawText) {
        return ApiResponse.ok(alertIngestionService.ingest(rawText), "analysis task accepted");
    }
}
