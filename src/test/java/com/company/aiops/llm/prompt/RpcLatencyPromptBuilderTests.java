package com.company.aiops.llm.prompt;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.analysis.bundle.CompressedEvidenceBundle;
import com.company.aiops.analysis.result.EvidenceReference;
import com.company.aiops.common.json.JsonMapper;
import com.company.aiops.llm.prompt.model.ServicePromptTemplateRecord;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RpcLatencyPromptBuilderTests {

    @Test
    void buildMessagesUsesServiceTemplateButKeepsFixedJsonContract() {
        RpcLatencyPromptBuilder builder = new RpcLatencyPromptBuilder(
                new JsonMapper(new ObjectMapper().findAndRegisterModules()),
                (serviceName, category) -> Optional.of(new ServicePromptTemplateRecord(
                        2L,
                        "latency-service",
                        AlertCategory.RPC_LATENCY,
                        "你是 latency-service 专属时延分析助手。",
                        "请优先分析缓存、CSE 和下游支付链路。",
                        true,
                        "v1",
                        null,
                        "seed",
                        "seed",
                        Instant.parse("2026-06-16T00:00:00Z"),
                        Instant.parse("2026-06-16T00:00:00Z")
                ))
        );

        String combinedPrompt = String.join("\n", builder.buildMessages(compressedEvidence(), null).stream()
                .map(message -> message.content())
                .toList());

        assertThat(combinedPrompt).contains("你是 latency-service 专属时延分析助手");
        assertThat(combinedPrompt).contains("请优先分析缓存、CSE 和下游支付链路");
        assertThat(combinedPrompt).contains("\"recommendations\"");
        assertThat(combinedPrompt).contains("priority 必须是 P0 / P1 / P2 / P3");
        assertThat(combinedPrompt).contains("不要输出 Markdown，不要输出 JSON 之外的任何文本");
    }

    private CompressedEvidenceBundle compressedEvidence() {
        Instant occurTime = Instant.parse("2026-04-21T02:33:00Z");
        return new CompressedEvidenceBundle(
                new CompressedEvidenceBundle.AlertSnapshot(
                        AlertCategory.RPC_LATENCY,
                        "latency-service",
                        "demo.Interface.query",
                        "cluster1",
                        occurTime,
                        "alert.request.count",
                        100.0,
                        "alert.fail.rate",
                        null,
                        "alert.avg.latency",
                        1200L,
                        "alert.p99.latency",
                        3200L,
                        "alert.failure.count",
                        null,
                        "alert.log.level",
                        null,
                        "alert.log.message",
                        null,
                        "alert.log.normalized_query",
                        null
                ),
                new CompressedEvidenceBundle.BaselineSnapshot(true, null, "baseline.request.count", "baseline.fail.rate", "baseline.avg.latency", "baseline.p99.latency", InterfaceBaseline.empty()),
                new CompressedEvidenceBundle.InterfaceLogSnapshot(true, null, "interface.request.count", 100, "interface.failure.rate", 0, 0.0, "interface.latency.profile", 1200L, 1800L, 3200L, 4100L, List.of(), List.of(), List.of()),
                new CompressedEvidenceBundle.TraceSnapshot(true, null, List.of(), List.of(), List.of()),
                new CompressedEvidenceBundle.RuntimeLogSnapshot(true, null, List.of(), false, null, List.of(), List.of()),
                new CompressedEvidenceBundle.MetricSnapshot(true, null, ContainerMetrics.empty(), CseMetrics.empty(), DatabaseMetrics.empty(), DcsMetrics.empty(), MqMetrics.empty(), List.of()),
                ResourceMapping.empty("latency-service", "cluster1"),
                new CompressedEvidenceBundle.RuleSnapshot(List.of(), List.of()),
                List.of(new EvidenceReference("alert.p99.latency", "ALERT", "p99 latency")),
                List.of(),
                List.of()
        );
    }
}
