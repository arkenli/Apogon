package com.company.aiops.analysis.query.model;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.analysis.job.AnalysisJobStatus;
import com.company.aiops.analysis.result.AnalysisMode;
import com.company.aiops.analysis.result.HistoricalReference;
import com.company.aiops.analysis.result.ImpactAssessment;
import com.company.aiops.analysis.result.MissingEvidence;
import com.company.aiops.analysis.result.Recommendation;
import com.company.aiops.analysis.result.RootCauseHypothesis;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisTaskQueryResponseTests {

    @Test
    void toChineseResultTextDescribesCompletedAnalysisWithSummaryAndDetails() {
        AnalysisTaskQueryResponse response = new AnalysisTaskQueryResponse(
                "task-1",
                "alert-1",
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
                        List.of("trace evidence is limited"),
                        true,
                        "SUCCESS",
                        "fallback-rules-v1",
                        0.78,
                        Instant.parse("2026-04-26T02:00:30Z")
                )
        );

        String text = response.toChineseResultText();

        assertThat(text)
                .contains("分析任务已完成")
                .contains("告警类型：RPC错误率")
                .contains("服务：ams_front")
                .contains("接口：vmall.Interface.query")
                .contains("集群：cluster1")
                .contains("结论摘要：query summary")
                .contains("影响评估：LOW（impact）")
                .contains("整体置信度：78%")
                .contains("分析方式：LLM辅助分析")
                .contains("LLM状态：SUCCESS")
                .contains("是否降级：否")
                .contains("可能根因：")
                .contains("1. business validation failure（置信度 74%）")
                .contains("处理建议：")
                .contains("1. [P1] check logs（目标：error.top.1）")
                .contains("缺失证据：")
                .contains("runtimeLog：timeout")
                .contains("证据不足：")
                .contains("trace evidence is limited")
                .contains("历史参考：historical summary");
    }

    @Test
    void toChineseResultTextDescribesRunningTaskWithoutResult() {
        AnalysisTaskQueryResponse response = new AnalysisTaskQueryResponse(
                "task-running",
                "alert-running",
                AnalysisJobStatus.RUNNING,
                AlertCategory.RPC_LATENCY,
                "cache_service",
                "persistent://demo",
                "cluster2",
                Instant.parse("2026-04-26T03:00:00Z"),
                Instant.parse("2026-04-26T03:00:02Z"),
                null,
                "analysis.collect-evidence",
                "collecting provider evidence",
                null,
                null
        );

        String text = response.toChineseResultText();

        assertThat(text)
                .contains("分析任务正在执行中")
                .contains("告警类型：RPC时延")
                .contains("当前阶段：analysis.collect-evidence")
                .contains("状态说明：collecting provider evidence");
    }

    @Test
    void toChineseResultTextDescribesServiceErrorLogCategory() {
        AnalysisTaskQueryResponse response = new AnalysisTaskQueryResponse(
                "task-service-log",
                "alert-service-log",
                AnalysisJobStatus.SUCCESS,
                AlertCategory.SERVICE_ERROR_LOG,
                "ams_front",
                null,
                "cluster2",
                Instant.parse("2026-06-22T05:42:00Z"),
                Instant.parse("2026-06-22T05:42:01Z"),
                Instant.parse("2026-06-22T05:42:30Z"),
                "analysis.complete",
                "completed",
                null,
                null
        );

        String text = response.toChineseResultText();

        assertThat(text)
                .contains("告警类型：服务错误日志")
                .contains("服务：ams_front")
                .doesNotContain("未知类型");
    }

    @Test
    void toChineseResultTextDescribesFailedTask() {
        AnalysisTaskQueryResponse response = new AnalysisTaskQueryResponse(
                "task-failed",
                "alert-failed",
                AnalysisJobStatus.FAILED,
                AlertCategory.RPC_ERROR_RATE,
                "order_service",
                "demo.Interface.submit",
                "cluster3",
                Instant.parse("2026-04-26T04:00:00Z"),
                Instant.parse("2026-04-26T04:00:02Z"),
                Instant.parse("2026-04-26T04:00:10Z"),
                "analysis.llm-analyze",
                "llm analyzing",
                "provider timeout",
                null
        );

        String text = response.toChineseResultText();

        assertThat(text)
                .contains("分析任务执行失败")
                .contains("告警类型：RPC错误率")
                .contains("失败原因：provider timeout");
    }
}
