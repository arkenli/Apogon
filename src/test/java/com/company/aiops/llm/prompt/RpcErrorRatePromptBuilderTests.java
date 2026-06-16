package com.company.aiops.llm.prompt;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.analysis.bundle.CompressedEvidenceBundle;
import com.company.aiops.analysis.result.EvidenceReference;
import com.company.aiops.common.json.JsonMapper;
import com.company.aiops.provider.model.ContainerMetrics;
import com.company.aiops.provider.model.CseMetrics;
import com.company.aiops.provider.model.DatabaseMetrics;
import com.company.aiops.provider.model.DcsMetrics;
import com.company.aiops.provider.model.InterfaceBaseline;
import com.company.aiops.provider.model.MqMetrics;
import com.company.aiops.provider.model.ResourceMapping;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RpcErrorRatePromptBuilderTests {

    @Test
    void buildMessagesHighlightsInternalErrorAsCodeIssueWithoutExplicitBusinessEvidence() {
        RpcErrorRatePromptBuilder builder = new RpcErrorRatePromptBuilder(
                new JsonMapper(new ObjectMapper().findAndRegisterModules())
        );

        List<String> contents = builder.buildMessages(compressedEvidence(), null).stream()
                .map(message -> message.content())
                .toList();
        String combinedPrompt = String.join("\n", contents);

        assertThat(combinedPrompt).contains("系统内部错误");
        assertThat(combinedPrompt).contains("优先按需要处理的代码问题或系统故障分析");
        assertThat(combinedPrompt).contains("只有在日志或其他证据明确表明这是正常业务校验导致时");
        assertThat(combinedPrompt).contains("仅出现业务功能名、业务场景词或 feature 描述");
    }

    private CompressedEvidenceBundle compressedEvidence() {
        Instant occurTime = Instant.parse("2026-04-21T02:33:00Z");
        return new CompressedEvidenceBundle(
                new CompressedEvidenceBundle.AlertSnapshot(
                        AlertCategory.RPC_ERROR_RATE,
                        "demo-service",
                        "demo.Interface.query",
                        "cluster1",
                        occurTime,
                        "alert.request.count",
                        6.0,
                        "alert.fail.rate",
                        0.8,
                        "alert.avg.latency",
                        null,
                        "alert.p99.latency",
                        null
                ),
                new CompressedEvidenceBundle.BaselineSnapshot(
                        true,
                        null,
                        "baseline.request.count",
                        "baseline.fail.rate",
                        "baseline.avg.latency",
                        "baseline.p99.latency",
                        InterfaceBaseline.empty()
                ),
                new CompressedEvidenceBundle.InterfaceLogSnapshot(
                        true,
                        null,
                        "interface.request.count",
                        6,
                        "interface.failure.rate",
                        5,
                        0.8,
                        "interface.latency.profile",
                        0L,
                        0L,
                        0L,
                        0L,
                        List.of(),
                        List.of(),
                        List.of()
                ),
                new CompressedEvidenceBundle.TraceSnapshot(true, null, List.of(), List.of(), List.of()),
                new CompressedEvidenceBundle.RuntimeLogSnapshot(true, null, List.of()),
                new CompressedEvidenceBundle.MetricSnapshot(
                        true,
                        null,
                        ContainerMetrics.empty(),
                        CseMetrics.empty(),
                        DatabaseMetrics.empty(),
                        DcsMetrics.empty(),
                        MqMetrics.empty(),
                        List.of()
                ),
                ResourceMapping.empty("demo-service", "cluster1"),
                new CompressedEvidenceBundle.RuleSnapshot(List.of(), List.of()),
                List.of(new EvidenceReference("alert.request.count", "ALERT", "request count")),
                List.of(),
                List.of()
        );
    }
}
