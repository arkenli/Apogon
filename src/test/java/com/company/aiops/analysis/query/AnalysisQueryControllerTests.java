package com.company.aiops.analysis.query;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.analysis.job.AnalysisJobStatus;
import com.company.aiops.analysis.query.controller.AnalysisQueryController;
import com.company.aiops.analysis.query.model.AnalysisResultView;
import com.company.aiops.analysis.query.model.AnalysisTaskQueryResponse;
import com.company.aiops.analysis.query.service.AnalysisQueryService;
import com.company.aiops.analysis.result.AnalysisMode;
import com.company.aiops.analysis.result.HistoricalReference;
import com.company.aiops.analysis.result.ImpactAssessment;
import com.company.aiops.analysis.result.MissingEvidence;
import com.company.aiops.analysis.result.Recommendation;
import com.company.aiops.analysis.result.RootCauseHypothesis;
import com.company.aiops.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AnalysisQueryController.class)
@Import(GlobalExceptionHandler.class)
class AnalysisQueryControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AnalysisQueryService analysisQueryService;

    @Test
    void getTaskReturnsSerializedTaskAndResult() throws Exception {
        when(analysisQueryService.getTask("task-1")).thenReturn(response("task-1", "alert-1"));

        mockMvc.perform(get("/api/v1/analysis/tasks/task-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.taskId").value("task-1"))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.result.analysisMode").value("LLM_ASSISTED"))
                .andExpect(jsonPath("$.data.result.rootCauses[0].causeType").value("BUSINESS_ERROR_CODE_NOT_MASKED"))
                .andExpect(jsonPath("$.data.result.historicalReference.taskId").value("task-history"));

        verify(analysisQueryService).getTask("task-1");
    }

    @Test
    void getLatestDelegatesToService() throws Exception {
        when(analysisQueryService.getLatestByAlertId("alert-duplicate")).thenReturn(response("task-original", "alert-original"));

        mockMvc.perform(get("/api/v1/analysis/latest").param("alertId", "alert-duplicate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value("task-original"))
                .andExpect(jsonPath("$.data.alertId").value("alert-original"));

        verify(analysisQueryService).getLatestByAlertId("alert-duplicate");
    }

    @Test
    void getHistoryReturnsListOrderedByServiceResponse() throws Exception {
        when(analysisQueryService.getHistory("history-service", "history.Interface.query", 2)).thenReturn(List.of(
                response("task-newer", "alert-newer"),
                response("task-older", "alert-older")
        ));

        mockMvc.perform(get("/api/v1/analysis/history")
                        .param("serviceName", "history-service")
                        .param("interfaceName", "history.Interface.query")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].taskId").value("task-newer"))
                .andExpect(jsonPath("$.data[1].taskId").value("task-older"));

        verify(analysisQueryService).getHistory("history-service", "history.Interface.query", 2);
    }

    @Test
    void getHistoryRejectsInvalidLimit() throws Exception {
        mockMvc.perform(get("/api/v1/analysis/history")
                        .param("serviceName", "history-service")
                        .param("interfaceName", "history.Interface.query")
                        .param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private AnalysisTaskQueryResponse response(String taskId, String alertId) {
        return new AnalysisTaskQueryResponse(
                taskId,
                alertId,
                AnalysisJobStatus.SUCCESS,
                AlertCategory.RPC_ERROR_RATE,
                "ams_front",
                "vmall.Interface.query",
                "cluster1",
                Instant.parse("2026-04-26T02:00:00Z"),
                Instant.parse("2026-04-26T02:00:01Z"),
                Instant.parse("2026-04-26T02:00:30Z"),
                "analysis.complete",
                "completed",
                null,
                new AnalysisResultView(
                        AnalysisMode.LLM_ASSISTED,
                        false,
                        null,
                        false,
                        List.of(new MissingEvidence("runtimeLog", "timeout", false, "detail missing")),
                        new HistoricalReference(
                                true,
                                "alert-history",
                                "task-history",
                                AnalysisMode.LLM_ASSISTED,
                                "historical summary",
                                0.81,
                                Instant.parse("2026-04-26T01:30:00Z")
                        ),
                        List.of(new RootCauseHypothesis(
                                "BUSINESS_ERROR_CODE_NOT_MASKED",
                                "business validation failure",
                                0.74,
                                List.of("error.top.1"),
                                "MERGED",
                                "reasoning"
                        )),
                        List.of(new Recommendation("P1", "CHECK_LOG", "check logs", "error.top.1")),
                        "query summary",
                        new ImpactAssessment("LOW", "impact"),
                        List.of(),
                        List.of(),
                        true,
                        "SUCCESS",
                        null,
                        0.78,
                        Instant.parse("2026-04-26T02:00:30Z")
                )
        );
    }
}
