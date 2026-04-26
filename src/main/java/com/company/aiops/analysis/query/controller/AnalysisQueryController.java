package com.company.aiops.analysis.query.controller;

import com.company.aiops.analysis.query.model.AnalysisTaskQueryResponse;
import com.company.aiops.analysis.query.service.AnalysisQueryService;
import com.company.aiops.common.api.ApiResponse;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Exposes the phase-9 task/result lookup APIs described in the design document.
 */
@Validated
@RestController
@RequestMapping("/api/v1/analysis")
public class AnalysisQueryController {

    private final AnalysisQueryService analysisQueryService;

    public AnalysisQueryController(AnalysisQueryService analysisQueryService) {
        this.analysisQueryService = analysisQueryService;
    }

    /**
     * Loads the latest persisted state for one task id. Running tasks return status metadata
     * without a final result payload.
     */
    @GetMapping("/tasks/{taskId}")
    public ApiResponse<AnalysisTaskQueryResponse> getTask(@PathVariable @NotBlank String taskId) {
        return ApiResponse.ok(analysisQueryService.getTask(taskId));
    }

    /**
     * Resolves the newest task/result for the alert. Duplicate alerts transparently reuse the
     * original task recorded in {@code aiops_duplicate_alert_link}.
     */
    @GetMapping("/latest")
    public ApiResponse<AnalysisTaskQueryResponse> getLatest(@RequestParam @NotBlank String alertId) {
        return ApiResponse.ok(analysisQueryService.getLatestByAlertId(alertId));
    }

    /**
     * Lists completed historical results for one service/interface pair.
     */
    @GetMapping("/history")
    public ApiResponse<List<AnalysisTaskQueryResponse>> getHistory(
            @RequestParam @NotBlank String serviceName,
            @RequestParam @NotBlank String interfaceName,
            @RequestParam(defaultValue = "20") @Min(1) int limit
    ) {
        return ApiResponse.ok(analysisQueryService.getHistory(serviceName, interfaceName, limit));
    }
}
